# stationeers-ktrapic

A system to allow IC10 programming using a constrained Kotlin (inspired by
[PyTrapIC](https://github.com/aproposmath/stationeers-pytrapic)).

KTrapIC compiles a constrained subset of Kotlin to IC10, the assembly language of
[Stationeers](https://store.steampowered.com/app/544550/Stationeers/)' programmable chips.
It is at an early stage: programs using values, loops, conditions, functions and the devices
on a chip's pins compile (see `test/cases/satellite_tracker.ic10.kts`, a port of a chip from the
game); batch operations and the IC10 Editor plugin are next.

Programs are Kotlin scripts named `*.ic10.kts`. The `ktrapic` API needs no import, since
`import ktrapic.*` is implied; writing it anyway is fine.

A function called once, or marked `inline`, is compiled into the place it is called. Any other
function gets one body, called with `jal`; its arguments and result travel in registers, and it
saves `ra` on the stack only if it calls another function. Recursion, default arguments, and
extension, generic and local functions are not supported, and only an inlined function can
take a `Device`. A call made before a property the function reads is declared is an error when
the call is inlined; a function called with `jal` compiles, and reads the property before its
initializer has run.

- `compiler/api`: `Compiler.compile(source, options)`, the whole pipeline from source to IC10.
- `compiler/frontend`: runs the Kotlin K2 front end on a script and returns its IR.
- `compiler/backend`: translates that IR to IC10, allocating registers r0–r15.
- `daemon`: the compile daemon, which reads requests on stdin and writes results on stdout.
- `compiler/ic10`: the IC10 program model, parser, and printer (normal, and compact with
  labels resolved to line numbers), plus the chip's size limits.
- `emu`: an IC10 emulator with fake devices, so tests can check what a program does.
- `playground`: a Kotlin script serving a local editor page backed by the daemon.
- `runtime`: the `ktrapic` API that programs compile against (devices, logic types, `sleep`,
  `hash`, ...). Part of it is generated from the game data in `runtime/data` by
  `tools/datagen`.
- `runtime/script`: the Kotlin script definition for `*.ic10.kts`, so IDEs check programs against
  the `ktrapic` API.

## Playground

A local editor page that compiles as you type, showing the IC10 next to the source with its line,
byte and register counts, and errors at their line. It needs the Kotlin command-line compiler
(`kotlin`) and a JDK; from the repository root:

```
bazel build //daemon:ktrapic-daemon_deploy.jar
kotlin playground/playground.main.kts
```

then open http://127.0.0.1:8765/. The Open menu lists the programs in `test/cases`; pass
`--source-dir` (repeatable) to list others, and `--port` to use another port. The script starts
the compile daemon (`daemon/`) and sends it each compile; the daemon speaks PyTrapIC's protocol
(one base64 JSON line per request and per response), which the IC10 Editor plugin will use too.
The first compile after starting takes a few seconds while the compiler warms up.

## Editing in IntelliJ

IntelliJ IDEA's Kotlin plugin understands `*.ic10.kts` files through KTrapIC's script definition:
it then completes and type-checks programs against the `ktrapic` API, with `import ktrapic.*`
implied. Build the two jars it needs:

```
bazel build //runtime //runtime/script:ktrapic-script
```

and add `bazel-bin/runtime/runtime.jar` and `bazel-bin/runtime/script/ktrapic-script.jar` to
your project as a library (Project Structure > Libraries; keep the file names, since the
definition finds the runtime by the name `runtime`). IntelliJ discovers the definition from the
jar; if it asks, allow it under Settings > Languages & Frameworks > Kotlin > Kotlin Scripting.
IntelliJ checks Kotlin only: KTrapIC's own rules (supported constructs, register and size
limits) come from the compiler, the playground or the CLI.

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
- Bitwise operations and shifts work on 53-bit integers with a sign: a value wraps modulo 2^53,
  a result keeps bits 0–52 with bit 53 as its sign, and `ushr` shifts the value as an unsigned
  54-bit number (`-1 ushr 1` is 2^53 - 1). They match the JVM for non-negative values whose
  results stay in the `Int` range, with shift counts between 0 and 31. At run time a count
  outside that range isn't reduced modulo 32 (`5 shr 32` is 0, not 5). A constant shift count
  outside 0..31, or a constant bitwise expression whose result differs from the JVM's (such as
  `-1 ushr 28`), is a compile error.
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

Constant expressions fold to the same results the chip computes. A NaN constant is computed as
`0 / 0` in the output, since the chip rejects `nan` written as an operand.

The line and byte counts the compiler reports are measured as the game's code editor
measures them: each line break counts as two characters toward the 4096 limit, so a 128-line
program has 3842 characters for its text.

## Building

KTrapIC builds with [Bazel](https://bazel.build) (bzlmod), via
[Bazelisk](https://github.com/bazelbuild/bazelisk), which picks the version pinned in
`.bazelversion`. Bazel downloads its own JDK 21 and Kotlin compiler.

```
bazel test //...
bazel run //cli:ktrapic -- [--compact] program.ic10.kts
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

Golden programs live in `test/cases`: each `X.ic10.kts` must compile to `X.ic10`, and to
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
