"""Kotlin JUnit 5 tests: one test target per test source file."""

load("@rules_java//java:java_test.bzl", "java_test")
load("@rules_kotlin//kotlin:jvm.bzl", "kt_jvm_library")

_TEST_ROOT = "src/test/kotlin/"

_JUNIT5_RUNTIME = [
    "@maven//:org_junit_jupiter_junit_jupiter_engine",
    "@maven//:org_junit_platform_junit_platform_console",
]

def kt_junit5_tests(name, srcs, associates = [], deps = [], size = "small", **kwargs):
    """Compiles Kotlin test sources once and runs each file's class as its own test target.

    Every file in `srcs` must live under `src/test/kotlin/` and declare one test class named
    after the file, in the package matching its directory. A target per file means a new test
    file is picked up by the glob in `srcs` and run, instead of compiling but never being
    selected by a single hand-written `test_class`. Only the class named after the file is
    selected: a second test class in the same file compiles but never runs, and nothing reports it.

    Tests run under JUnit's ConsoleLauncher with --fail-if-no-tests, so a target whose class
    has no JUnit 5 tests (for example one written with JUnit 4's org.junit.Test) fails rather
    than passing with zero tests run. Results appear in the target's test.log; Bazel records
    one pass/fail per target (no per-method XML), and --test_filter is not applied, so a
    filtered run executes the whole class.

    Args:
      name: name of the compiled test library; test targets are named after their classes.
      srcs: Kotlin test sources.
      associates: libraries whose `internal` members the tests may use.
      deps: compile-time dependencies (Truth, the JUnit Jupiter API, ...).
      size: Bazel test size for every generated target.
      **kwargs: passed to each java_test.
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
        java_test(
            name = class_path.split("/")[-1],
            size = size,
            main_class = "org.junit.platform.console.ConsoleLauncher",
            use_testrunner = False,
            args = [
                "execute",
                "--select-class=" + class_path.replace("/", "."),
                "--fail-if-no-tests",
                "--disable-banner",
                "--details=tree",
            ],
            runtime_deps = [":" + name] + _JUNIT5_RUNTIME,
            **kwargs
        )
