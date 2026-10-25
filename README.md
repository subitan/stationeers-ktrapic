# stationeers-ktrapic

A system to allow IC10 programming using a constrained Kotlin (inspired by
[PyTrapIC](https://github.com/aproposmath/stationeers-pytrapic)).

KTrapIC compiles a constrained subset of Kotlin to IC10, the assembly language of
[Stationeers](https://store.steampowered.com/app/544550/Stationeers/)' programmable chips.
It is at an early stage: programs using values, loops, conditions and the devices on a
chip's pins compile; functions, batch operations and the IC10 Editor plugin are next.

- `compiler/api`: `Compiler.compile(source, options)`, the whole pipeline from source to IC10.
- `compiler/frontend`: runs the Kotlin K2 front end on a script and returns its IR.
- `compiler/backend`: translates that IR to IC10, allocating registers r0–r15.
- `compiler/ic10`: the IC10 program model, parser, and printer (normal, and compact with
  labels resolved to line numbers), plus the chip's size limits.
- `emu`: an IC10 emulator with fake devices, so tests can check what a program does.
- `runtime`: the `ktrapic` API that programs compile against (devices, logic types, `sleep`,
  `hash`, ...). Part of it is generated from the game data in `runtime/data` by
  `tools/datagen`.

## Numbers

IC10 has one kind of number, a 64-bit floating-point number, and Kotlin's `Double` is exactly
that: arithmetic, comparisons, NaN and infinity behave on the chip as they do on the JVM. Use
`Double` for measurements, settings and anything a device reports.

`Int` is for counting and whole numbers within the `Int` range. It compiles to the same kind of
number, and behaves as on the JVM while it stays in that range: `/` truncates, `%` takes the
dividend's sign, and `toInt()` truncates, limits the result to the `Int` range and turns NaN
into 0. `roundToInt()` limits its result the same way. Where the chip can't match the JVM:

- Arithmetic past the `Int` range doesn't wrap: an `Int` holding 2147483647 becomes 2147483648
  when incremented. A constant expression that would overflow is a compile error instead.
- Bitwise operations and shifts work on 64-bit integers, so they match the JVM only while the
  result stays in the `Int` range and the shift count is between 0 and 31. At run time a count
  outside that range isn't reduced modulo 32 (`x shr 32` is 0, not `x`); a constant one is a
  compile error.
- `roundToInt()` of NaN gives 0, where the JVM throws.
- An `Int` product or quotient that is zero can be a negative zero (`0 * -1`, `0 / -5`). It
  equals 0, but the sign shows once it becomes a `Double`: `1.0 / n.toDouble()` is negative
  infinity.
- Dividing an `Int` by zero at run time gives NaN or infinity, where the JVM throws. An `Int`
  holding NaN compares as a `Double` NaN does: every ordered comparison is false, and a `for`
  loop with a NaN bound runs no iterations. (Dividing by a constant zero is a compile error.)
- `Double` `%` is computed as `a - truncate(a / b) * b`, which rounds at the division and the
  multiplication, so it can differ from the JVM's exact remainder: `10.0 % 0.1` is 0.0 rather
  than 0.09999999999999945, and `x % Double.POSITIVE_INFINITY` is NaN rather than `x`.

Constant expressions fold to the same results the chip computes.

## Building

KTrapIC builds with [Bazel](https://bazel.build) (bzlmod), via
[Bazelisk](https://github.com/bazelbuild/bazelisk), which picks the version pinned in
`.bazelversion`. Bazel downloads its own JDK 21 and Kotlin compiler.

```
bazel test //...
bazel run //cli:ktrapic -- [--compact] program.kts
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

### Game data

`runtime/data/logic-enums.json` holds the game's logic enums; `runtime/data/typing.json` says
how the API types them. After changing either, regenerate the runtime's sources (a test fails
until they match):

```
bazel run //tools/datagen -- generate
```

The enums were imported from [PyTrapIC](https://github.com/aproposmath/stationeers-pytrapic)'s
generated types with
`bazel run //tools/datagen -- import-pytrapic <types_generated.py> <source description>`.
PyTrapIC is under the MIT License; its notice is in `runtime/data/PYTRAPIC-LICENSE`.

## Formatting

Kotlin sources are formatted with [ktfmt](https://github.com/facebook/ktfmt) 0.64 (default
style); CI fails on unformatted code, except the programs in `test/cases`, which are
test input. With the
[`ktfmt-0.64-with-dependencies.jar`](https://repo1.maven.org/maven2/com/facebook/ktfmt/0.64/)
from Maven Central:

```
java -jar ktfmt-0.64-with-dependencies.jar <files...>
```

## Tests

Golden programs live in `test/cases`: each `X.kts` must compile to `X.ic10`, and to
`X.compact.ic10` with compact output. After a deliberate change to the output, rewrite them
(the path must be absolute):

```
bazel test //compiler/api:GoldenTest --test_env=KTRAPIC_UPDATE_GOLDENS=$PWD/test/cases
```

In Git Bash with `MSYS_NO_PATHCONV=1`, `$PWD` is a `/d/...` path the JVM can't open: use
`$(pwd -W)/test/cases` instead.

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
