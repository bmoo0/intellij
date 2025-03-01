/*
 * Copyright 2022 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.base.qsync;

import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.idea.blaze.base.model.primitives.WorkspaceRoot;
import com.google.idea.blaze.base.projectview.ProjectViewSet;
import com.google.idea.blaze.base.settings.BlazeImportSettings;
import com.google.idea.blaze.base.sync.BlazeSyncPlugin;
import com.google.idea.blaze.base.sync.data.BlazeDataStorage;
import com.google.idea.blaze.base.sync.projectview.LanguageSupport;
import com.google.idea.blaze.base.sync.projectview.WorkspaceLanguageSettings;
import com.google.idea.blaze.base.util.UrlUtil;
import com.google.idea.blaze.common.Context;
import com.google.idea.blaze.common.PrintOutput;
import com.google.idea.blaze.qsync.BlazeProjectListener;
import com.google.idea.blaze.qsync.project.BlazeProjectSnapshot;
import com.google.idea.blaze.qsync.project.ProjectProto;
import com.google.idea.common.util.Transactions;
import com.intellij.openapi.externalSystem.service.project.IdeModifiableModelsProvider;
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.ModuleType;
import com.intellij.openapi.module.ModuleTypeManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ContentEntry;
import com.intellij.openapi.roots.DependencyScope;
import com.intellij.openapi.roots.LibraryOrderEntry;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.roots.SourceFolder;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.roots.libraries.Library.ModifiableModel;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.jetbrains.jps.model.java.JavaSourceRootProperties;
import org.jetbrains.jps.model.java.JavaSourceRootType;
import org.jetbrains.jps.model.java.JpsJavaExtensionService;

/** An object that monitors the build graph and applies the changes to the project structure. */
public class ProjectUpdater implements BlazeProjectListener {

  private Project project;
  private final BlazeImportSettings importSettings;
  private final ProjectViewSet projectViewSet;
  private final WorkspaceRoot workspaceRoot;

  public ProjectUpdater(
      Project project,
      BlazeImportSettings importSettings,
      ProjectViewSet projectViewSet,
      WorkspaceRoot workspaceRoot) {
    this.project = project;
    this.importSettings = importSettings;
    this.projectViewSet = projectViewSet;
    this.workspaceRoot = workspaceRoot;
  }

  public static ModuleType<?> mapModuleType(ProjectProto.ModuleType type) {
    switch (type) {
      case MODULE_TYPE_DEFAULT:
        return ModuleTypeManager.getInstance().getDefaultModuleType();
      case UNRECOGNIZED:
        break;
    }
    throw new IllegalStateException("Unrecognised module type " + type);
  }

  @Override
  public void graphCreated(Context context, BlazeProjectSnapshot graph) throws IOException {

    updateProjectModel(graph.project(), context);
  }

  private void updateProjectModel(ProjectProto.Project spec, Context context) {
    ModuleManager moduleManager = ModuleManager.getInstance(project);
    File imlDirectory = new File(BlazeDataStorage.getProjectDataDir(importSettings), "modules");
    Path projectDirectory = Paths.get(Objects.requireNonNull(project.getBasePath()));
    Transactions.submitWriteActionTransactionAndWait(
        () -> {
          for (BlazeSyncPlugin syncPlugin : BlazeSyncPlugin.EP_NAME.getExtensions()) {
            syncPlugin.updateProjectSdk(project, context, projectViewSet);
          }

          IdeModifiableModelsProvider models =
              ProjectDataManager.getInstance().createModifiableModelsProvider(project);
          int removedLibCount = removeUnusedLibraries(models, spec.getLibraryList());
          if (removedLibCount > 0) {
            context.output(PrintOutput.output("Removed " + removedLibCount + " libs"));
          }
          ImmutableMap.Builder<String, Library> libMapBuilder = ImmutableMap.builder();
          for (ProjectProto.Library libSpec : spec.getLibraryList()) {
            Library library = getOrCreateLibrary(models, libSpec);
            libMapBuilder.put(libSpec.getName(), library);
          }
          ImmutableMap<String, Library> libMap = libMapBuilder.buildOrThrow();
          models.commit();

          for (ProjectProto.Module moduleSpec : spec.getModulesList()) {
            Module module =
                moduleManager.newModule(
                    imlDirectory.toPath().resolve(moduleSpec.getName() + ".iml"),
                    mapModuleType(moduleSpec.getType()).getId());

            ModifiableRootModel roots = ModuleRootManager.getInstance(module).getModifiableModel();
            // TODO: should this be encapsulated in ProjectProto.Module?
            roots.inheritSdk();

            // TODO instead of removing all content entries and re-adding, we should calculate the
            //  diff.
            for (ContentEntry entry : roots.getContentEntries()) {
              roots.removeContentEntry(entry);
            }
            for (ProjectProto.ContentEntry ceSpec : moduleSpec.getContentEntriesList()) {
              Path contentEntryBasePath;
              switch (ceSpec.getRoot().getBase()) {
                case PROJECT:
                  contentEntryBasePath = projectDirectory;
                  break;
                case WORKSPACE:
                  contentEntryBasePath = workspaceRoot.path();
                  break;
                default:
                  throw new IllegalStateException(
                      "Unrecognized content root base type " + ceSpec.getRoot().getBase());
              }

              ContentEntry contentEntry =
                  roots.addContentEntry(
                      UrlUtil.pathToIdeaUrl(
                          contentEntryBasePath.resolve(ceSpec.getRoot().getPath())));
              for (ProjectProto.SourceFolder sfSpec : ceSpec.getSourcesList()) {
                Path sourceFolderPath = contentEntryBasePath.resolve(sfSpec.getPath());

                JavaSourceRootProperties properties =
                    JpsJavaExtensionService.getInstance()
                        .createSourceRootProperties(
                            sfSpec.getPackagePrefix(), sfSpec.getIsGenerated());
                JavaSourceRootType rootType =
                    sfSpec.getIsTest() ? JavaSourceRootType.TEST_SOURCE : JavaSourceRootType.SOURCE;
                SourceFolder unused =
                    contentEntry.addSourceFolder(
                        UrlUtil.pathToIdeaUrl(sourceFolderPath), rootType, properties);
              }
              for (String exclude : ceSpec.getExcludesList()) {
                contentEntry.addExcludeFolder(
                    UrlUtil.pathToIdeaUrl(workspaceRoot.absolutePathFor(exclude)));
              }
            }

            for (String lib : moduleSpec.getLibraryNameList()) {
              Library library = libMap.get(lib);
              if (library == null) {
                throw new IllegalStateException(
                    "Module refers to library " + lib + " not present in the project spec");
              }
              LibraryOrderEntry entry = roots.addLibraryEntry(library);
              // TODO should this stuff be specified by the Module proto too?
              entry.setScope(DependencyScope.COMPILE);
              entry.setExported(false);
            }

            WorkspaceLanguageSettings workspaceLanguageSettings =
                LanguageSupport.createWorkspaceLanguageSettings(projectViewSet);

            for (BlazeSyncPlugin syncPlugin : BlazeSyncPlugin.EP_NAME.getExtensions()) {
              // TODO update ProjectProto.Module and updateProjectStructure() to allow a more
              // suitable
              //   data type to be passed in here instead of androidResourceDirectories and
              //   androidSourcePackages
              syncPlugin.updateProjectStructure(
                  project,
                  context,
                  workspaceRoot,
                  module,
                  ImmutableSet.copyOf(moduleSpec.getAndroidResourceDirectoriesList()),
                  ImmutableSet.copyOf(moduleSpec.getAndroidSourcePackagesList()),
                  workspaceLanguageSettings);
            }
            roots.commit();
          }
        });
  }

  private Library getOrCreateLibrary(
      IdeModifiableModelsProvider models, ProjectProto.Library libSpec) {
    // TODO this needs more work, it's a bit messy.
    Library library = models.getLibraryByName(libSpec.getName());
    if (library == null) {
      library = models.createLibrary(libSpec.getName());
    }
    Path projectBase = Paths.get(project.getBasePath());
    ImmutableMap<String, ProjectProto.JarDirectory> dirs =
        libSpec.getClassesJarList().stream()
            .collect(
                ImmutableMap.toImmutableMap(
                    d -> UrlUtil.pathToIdeaUrl(projectBase.resolve(d.getPath())),
                    Function.identity()));

    // make sure the library contains only jar directory urls we want
    ModifiableModel modifiableModel = library.getModifiableModel();

    Set<String> foundJarDirectories = Sets.newHashSet();
    for (String url : modifiableModel.getUrls(OrderRootType.CLASSES)) {
      if (modifiableModel.isJarDirectory(url) && dirs.containsKey(url)) {
        foundJarDirectories.add(url);
      } else {
        modifiableModel.removeRoot(url, OrderRootType.CLASSES);
      }
    }
    for (String notFound : Sets.difference(dirs.keySet(), foundJarDirectories)) {
      ProjectProto.JarDirectory dir = dirs.get(notFound);
      modifiableModel.addJarDirectory(notFound, dir.getRecursive(), OrderRootType.CLASSES);
    }
    modifiableModel.commit();
    return library;
  }

  /**
   * Removes any existing library that should not be used by this project e.g. inherit from old
   * project.
   */
  private int removeUnusedLibraries(
      IdeModifiableModelsProvider models, List<ProjectProto.Library> libraries) {
    ImmutableSet<String> librariesToKeep =
        libraries.stream().map(ProjectProto.Library::getName).collect(toImmutableSet());
    int removedLibCount = 0;
    for (Library library : models.getAllLibraries()) {
      if (!librariesToKeep.contains(library.getName())) {
        removedLibCount++;
        models.removeLibrary(library);
      }
    }
    return removedLibCount;
  }
}
