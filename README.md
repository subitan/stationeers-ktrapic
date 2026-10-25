# stationeers-ktrapic

A system to allow IC10 programming using a constrained Kotlin (inspired by
[PyTrapIC](https://github.com/aproposmath/stationeers-pytrapic)).

KTrapIC compiles a constrained subset of Kotlin to IC10, the assembly language of
[Stationeers](https://store.steampowered.com/app/544550/Stationeers/)' programmable chips.
It is at an early stage: the build is in place and the compiler is being written.

## Building

KTrapIC builds with [Bazel](https://bazel.build) (bzlmod), via
[Bazelisk](https://github.com/bazelbuild/bazelisk), which picks the version pinned in
`.bazelversion`. Bazel downloads its own JDK 21 and Kotlin compiler.

```
bazel test //...
bazel run //cli:ktrapic
```

### Dependencies

Maven dependencies are declared in `MODULE.bazel` and locked in `maven_install.json`. After
changing them, repin:

```
RULES_JVM_EXTERNAL_REPIN=1 bazel run @maven//:pin
```

### Windows

- Run Bazel from PowerShell or cmd. In Git Bash, path conversion can mangle `//labels`
  (set `MSYS_NO_PATHCONV=1` if you must use it).
- `bazel run @maven//:pin` builds the resolver but can't start it, because its launcher is
  a bash script. Run the launcher with bash from its runfiles directory instead: after
  `bazel build @maven//:pin`, from `bazel-bin/external/rules_jvm_external++maven+maven/pin.runfiles/_main`,
  run `bash ../rules_jvm_external++maven+maven/pin` with `BUILD_WORKSPACE_DIRECTORY` set to
  the workspace root and `RUNFILES_DIR` set to the `pin.runfiles` directory.
- Per-machine Bazel settings (for example moving Bazel's output root and caches off a full
  system drive) go in an untracked `user.bazelrc`, which `.bazelrc` imports:

  ```
  startup --output_user_root=D:/bazel
  common --repository_cache=D:/bazel-repo
  common --disk_cache=D:/bazel-disk
  ```

## Formatting

Kotlin sources are formatted with [ktfmt](https://github.com/facebook/ktfmt) 0.64 (default
style); CI fails on unformatted code. With the
[`ktfmt-0.64-with-dependencies.jar`](https://repo1.maven.org/maven2/com/facebook/ktfmt/0.64/)
from Maven Central:

```
java -jar ktfmt-0.64-with-dependencies.jar <files...>
```

## Tests

Kotlin tests use JUnit 5 + Truth. In a package's `BUILD.bazel`, `kt_junit5_tests` (from
`//build_defs/kotlin:junit5.bzl`) compiles `src/test/kotlin/**/*.kt` and creates one test
target per file, named after the file's class, so new test files run without editing the
build. Only the class named after the file runs, so put each test class in its own file: a
second class in the same file compiles but is silently skipped. Tests run under JUnit's
ConsoleLauncher with `--fail-if-no-tests`: use JUnit 5's `org.junit.jupiter.api.Test`.
JUnit 4's `org.junit.Test` compiles (Truth brings JUnit 4 onto the classpath) but never
runs: a class with only JUnit 4 tests finds none and its target fails, while JUnit 4 tests
mixed into a class with JUnit 5 tests are silently skipped.

## License

MIT; see [LICENSE](LICENSE).
