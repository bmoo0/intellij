load(
    "//build_defs:build_defs.bzl",
    "intellij_plugin",
    "intellij_plugin_library",
    "optional_plugin_xml",
    "stamped_plugin_xml",
)
load(
    "//testing:test_defs.bzl",
    "intellij_integration_test_suite",
)
load(
    "//:build-visibility.bzl",
    "PLUGIN_PACKAGES_VISIBILITY",
)
load("//intellij_platform_sdk:build_defs.bzl", "select_for_plugin_api")

licenses(["notice"])

java_library(
    name = "plugin_dev",
    srcs = glob(["src/**/*.java"]),
    deps = [
        "//base",
        "//common/experiments",
        "//common/util:transactions",
        "//intellij_platform_sdk:devkit",
        "//intellij_platform_sdk:jsr305",
        "//intellij_platform_sdk:plugin_api",
        "//java",
        "//proto:proto_deps",
        "//shared",
    ],
)

optional_plugin_xml(
    name = "optional_xml",
    module = "DevKit",
    plugin_xml = "src/META-INF/blaze-plugin-dev.xml",
)

intellij_plugin_library(
    name = "plugin_library",
    optional_plugin_xmls = [":optional_xml"],
    visibility = PLUGIN_PACKAGES_VISIBILITY,
    deps = [":plugin_dev"],
)

# This rule is necessary so that "plugin_library" can be included in ASwB builds based on 2022.1 and
# later only, where the "plugin devkit" is included in the base version of Studio. Delete is once we
# no longer support earlier versions.
alias(
    name = "plugin_library_for_aswb",
    actual = select_for_plugin_api({
        "android-studio-2021.3": ":empty",
        # 2022.1 and all future versions should include this.
        "default": "//plugin_dev:plugin_library",
    }),
    visibility = PLUGIN_PACKAGES_VISIBILITY,
)

intellij_plugin_library(
    name = "empty",
)

stamped_plugin_xml(
    name = "plugin_dev_plugin_xml",
    plugin_id = "com.google.idea.blaze.plugin_dev",
    plugin_name = "com.google.idea.blaze.plugin_dev",
)

intellij_plugin(
    name = "plugin_dev_integration_test_plugin",
    testonly = 1,
    plugin_xml = ":plugin_dev_plugin_xml",
    tags = [
        "incomplete-deps",  # remove this suppression and add any missing deps, see go/java-import-deps-checking-lsc
    ],
    deps = [
        ":plugin_library",
        "//base:plugin_library",
        "//java:plugin_library",
    ],
)

intellij_integration_test_suite(
    name = "integration_tests",
    srcs = glob(["tests/integrationtests/**/*.java"]),
    required_plugins = "com.google.idea.blaze.plugin_dev,DevKit",
    test_package_root = "com.google.idea.blaze.plugin",
    runtime_deps = [
        ":plugin_dev_integration_test_plugin",
    ],
    deps = [
        ":plugin_dev",
        "//base",
        "//base:integration_test_utils",
        "//base:unit_test_utils",
        "//intellij_platform_sdk:jsr305",
        "//intellij_platform_sdk:plugin_api_for_tests",
        "//intellij_platform_sdk:test_libs",
        "@junit//jar",
    ],
)
