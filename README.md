# stationeers-ktrapic

A system to allow IC10 programming using a constrained Kotlin (inspired by
[PyTrapIC](https://github.com/aproposmath/stationeers-pytrapic); see [Credits](#credits)).

KTrapIC compiles a constrained subset of Kotlin to IC10, the assembly language of
[Stationeers](https://store.steampowered.com/app/544550/Stationeers/)' programmable chips.
It is at an early stage: programs using values, loops, conditions, functions, the devices on a
chip's pins, typed devices, slots and batch operations on the network compile (see
`test/cases/satellite_tracker.ic10.kts`, a port of a chip from the game), and a plugin for the
IC10 Editor mod compiles them in game.

Programs are Kotlin scripts named `*.ic10.kts`. The `ktrapic` API needs no import, since
`import ktrapic.*` is implied; writing it anyway is fine.

The [language guide](docs/LANGUAGE.md) describes what programs can use: values, control flow,
functions, devices, the maths and numeric rules, and the chip's size limits. The
[device reference](docs/DEVICES.md) lists every prefab a program can name, with its logic types.

- `compiler/api`: `Compiler.compile(source, options)`, the whole pipeline from source to IC10.
- `compiler/frontend`: runs the Kotlin K2 front end on a script and returns its IR.
- `compiler/backend`: translates that IR to IC10, allocating registers r0–r15.
- `daemon`: the compile daemon, which reads requests on stdin and writes results on stdout,
  in PyTrapIC's line protocol or, with `--lsp`, the Language Server Protocol.
- `mod`: the plugin for the IC10 Editor mod (C#), which runs the daemon in game.
- `compiler/ic10`: the IC10 program model, parser, and printer (normal, and compact with
  labels resolved to line numbers), plus the chip's size limits.
- `emu`: an IC10 emulator with fake devices, so tests can check what a program does.
- `harness`: runs a program against a test file's network of fake devices on the emulator and
  checks the values it expects (see [docs/TESTING.md](docs/TESTING.md)).
- `playground`: a Kotlin script serving a local editor page backed by the daemon.
- `sdk`: the `ktrapic` API that programs compile against (devices, logic types, `sleep`,
  `hash`, ...). Part of it is generated from the game data in `sdk/data` by
  `tools/datagen`.
- `sdk/script`: the Kotlin script definition for `*.ic10.kts`, so IDEs check programs against
  the `ktrapic` API.

## Roadmap

| | Step | Status |
|---|---|---|
| 0 | Tooling, environment and repository guardrails | Done |
| 1 | Skeleton and builds: Bazel, CI on Linux and Windows | Done |
| 2 | The Kotlin K2 front end, run on a script | Done |
| 3 | IC10 model and emulator | Done |
| 4 | The basics end to end: values, control flow, functions, devices, slots, batches | Done |
| 5 | Compile daemon and playground | Done |
| 6 | IC10 Editor plugin: compiling and highlighting in game ([#41](https://github.com/subitan/stationeers-ktrapic/issues/41)) | Done |
| 7 | The typed API: views, static sets, families of related devices | In progress |
| 8 | Completion, hovers and diagnostics in the IC10 Editor ([#45](https://github.com/subitan/stationeers-ktrapic/issues/45)) | In progress |
| 9 | A test harness in the playground: run a program against fake devices ([#66](https://github.com/subitan/stationeers-ktrapic/issues/66)) | In progress |
| 10 | An IntelliJ plugin: KTrapIC's own errors and the compiled IC10 in the IDE ([#23](https://github.com/subitan/stationeers-ktrapic/issues/23)) | Planned |

## Credits

KTrapIC exists because of [aproposmath](https://github.com/aproposmath)'s work, and owes it a great
deal:

- **[PyTrapIC](https://github.com/aproposmath/stationeers-pytrapic)** is the project this one
  follows. Its idea of writing chips in a familiar language and compiling them to IC10, its compile
  options and result format, its daemon protocol, and its IC10 Editor plugin are the model for
  KTrapIC's; KTrapIC aims to behave and look like PyTrapIC in game, with Kotlin in place of
  Python. Its generated types and structures are, for now, the source of KTrapIC's device enums
  and prefabs (MIT License; see [Game data](#game-data)).
- **[IC10 Editor](https://github.com/aproposmath/StationeersIC10Editor)** is the mod KTrapIC's
  plugin is built on and needs: it provides the editor, the language plugins and the in-game
  experience that KTrapIC adds a language to.
- **[stationeers-modding-template](https://github.com/aproposmath/stationeers-modding-template)**
  provides the stand-in game DLLs that the plugin's CI build compiles against.

Thank you.

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
(one base64 JSON line per request and per response).
The first compile after starting takes a few seconds while the compiler warms up.

Started with `--lsp`, the daemon speaks the Language Server Protocol on stdin and stdout instead
(JSON-RPC with `Content-Length` framing). It keeps the documents a client opens and changes, and
compiles one on the custom request `ktrapic/compile`, whose params are
`{"textDocument": {"uri"}, "options": {"compact"}}` and whose result is the same JSON as a
line-protocol compile. 150 ms after a document is opened or changed it compiles it and sends
`textDocument/publishDiagnostics` for that version: the compile's errors (severity 1) or warnings
(severity 2), with ranges in UTF-16 code units, or an empty list once it compiles cleanly or is
closed. A newer change replaces a compile still pending or running, so diagnostics never arrive
for an older version, and a `ktrapic/compile` of the same text reuses that compile. From the
compiler's analysis of a document's current text, it answers `textDocument/completion` with what
could be written at a position, `textDocument/hover` with the declaration of the name there, its
class, whether it is read-only or write-only, and the SDK's KDoc for it, as plain text, and
`textDocument/signatureHelp` with the functions the call around a position could be to. It answers
`textDocument/semanticTokens/full` with no tokens, as the IC10 Editor asks for them after every
change. See `daemon/src/main/kotlin/ktrapic/daemon/lsp/LspServer.kt`. The IC10 Editor plugin
speaks this mode (see [mod/README.md](mod/README.md#how-the-plugin-talks-to-the-compiler)).

## In game

The plugin in `mod/` adds a "Kotlin" language to the
[IC10 Editor](https://github.com/aproposmath/StationeersIC10Editor) mod: programs are compiled as
you type, with the IC10 shown beside them and the compiler's errors marked in the code, and
exported to the chip as IC10 with the Kotlin source kept alongside, so the chip opens as Kotlin
again. On first use it downloads the compiler and a Java runtime for it from this repository's
GitHub release. See [mod/README.md](mod/README.md)
for installing, using and building it.

## Editing in IntelliJ

IntelliJ IDEA's Kotlin plugin understands `*.ic10.kts` files through KTrapIC's script definition:
it then completes and type-checks programs against the `ktrapic` API, with `import ktrapic.*`
implied. Build the two jars it needs:

```
bazel build //sdk //sdk/script:ktrapic-script
```

and add `bazel-bin/sdk/sdk.jar` and `bazel-bin/sdk/script/ktrapic-script.jar` to
your project as a library (Project Structure > Libraries; keep the file names, since the
definition finds the SDK by the name `sdk`). IntelliJ discovers the definition from the
jar; if it asks, allow it under Settings > Languages & Frameworks > Kotlin > Kotlin Scripting.
IntelliJ checks Kotlin only: KTrapIC's own rules (supported constructs, register and size
limits) come from the compiler, the playground or the CLI.

## Numbers

The chip has one kind of number, and KTrapIC's `Double` is exactly it. `Int` behaves as on the JVM
within the `Int` range; the [language guide](docs/LANGUAGE.md#numbers) lists the few places
where the chip differs, and
[how the line and byte counts are measured](docs/LANGUAGE.md#size-and-registers).

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

`sdk/data/logic-enums.json` holds the game's logic enums and `sdk/data/prefabs.json` its
prefabs with their logic types and slots, and what each kind of slot can read;
`sdk/data/typing.json` says how the API types logic and which slot logic types the game lets
a program write (checked against the game's code, which allows far fewer than PyTrapIC's data
claims), and `sdk/data/grouping.json` which prefabs are grouped into kinds (`Battery.Small`)
and which are text displays. After changing any of them, regenerate the SDK's sources (a test fails
until they match):

```
bazel run //tools/datagen -- generate
```

The enums and prefabs were imported from
[PyTrapIC](https://github.com/aproposmath/stationeers-pytrapic)'s generated types and structures
with `bazel run //tools/datagen -- import-pytrapic <types_generated.py> <source description>`
and `import-pytrapic-structures <structures_generated.py> <source description>`.
PyTrapIC is under the MIT License; its notice is in `sdk/data/PYTRAPIC-LICENSE`.

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

An `X.test.json` beside a program is a test of it against fake devices (see
[docs/TESTING.md](docs/TESTING.md)); `//compiler/api:CaseTestsTest` runs each one against
the program compiled both ways.

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
