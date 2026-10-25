"""Kotlin JUnit 5 tests: one java_junit5_test per test source file."""

load("@contrib_rules_jvm//java:defs.bzl", "java_junit5_test")
load("@rules_kotlin//kotlin:jvm.bzl", "kt_jvm_library")

_TEST_ROOT = "src/test/kotlin/"

_JUNIT5_RUNTIME = [
    "@maven//:org_junit_jupiter_junit_jupiter_engine",
    "@maven//:org_junit_platform_junit_platform_launcher",
    "@maven//:org_junit_platform_junit_platform_reporting",
]

def kt_junit5_tests(name, srcs, associates = [], deps = [], size = "small", **kwargs):
    """Compiles Kotlin test sources once and runs each file's class as its own test target.

    Every file in `srcs` must live under `src/test/kotlin/` and declare one test class named
    after the file, in the package matching its directory. A target per file means a new test
    file is picked up by the glob in `srcs` and run, instead of compiling but never being
    selected by a single hand-written `test_class`.

    Args:
      name: name of the compiled test library; test targets are named after their classes.
      srcs: Kotlin test sources.
      associates: libraries whose `internal` members the tests may use.
      deps: compile-time dependencies (Truth, the JUnit Jupiter API, ...).
      size: Bazel test size for every generated target.
      **kwargs: passed to each java_junit5_test.
    """
    kt_jvm_library(
        name = name,
        testonly = True,
        srcs = srcs,
        associates = associates,
        deps = deps,
    )
    for src in srcs:
        if not src.startswith(_TEST_ROOT) or not src.endswith(".kt"):
            fail("%s: test sources must be .kt files under %s, got %s" % (name, _TEST_ROOT, src))
        class_path = src[len(_TEST_ROOT):-len(".kt")]
        java_junit5_test(
            name = class_path.split("/")[-1],
            size = size,
            test_class = class_path.replace("/", "."),
            runtime_deps = [":" + name] + _JUNIT5_RUNTIME,
            **kwargs
        )
