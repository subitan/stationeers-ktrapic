# KTrapIC for the IC10 Editor

A plugin for the [IC10 Editor](https://github.com/aproposmath/StationeersIC10Editor) mod that adds
a "Kotlin" language to it, so chips can be programmed in KTrapIC's Kotlin (see the
[language guide](../docs/LANGUAGE.md)). It is a peer of, and modelled on,
[PyTrapIC](https://github.com/aproposmath/stationeers-pytrapic)'s plugin, which does the same for
Python. It tries to behave and look like PyTrapIC's plugin in game: it has the same
compile-as-you-type preview and the same export to the chip, and its compile daemon also speaks
PyTrapIC's daemon protocol (with small differences, such as error positions being 1-based here),
although the plugin talks to it over the Language Server Protocol (see
[How the plugin talks to the compiler](#how-the-plugin-talks-to-the-compiler)). Both
PyTrapIC and the IC10 Editor, which this plugin is built on and needs, are by
[aproposmath](https://github.com/aproposmath). Thank you to aproposmath for both, and for the
[modding template](https://github.com/aproposmath/stationeers-modding-template) whose stand-in game
DLLs the plugin's CI build compiles against.

## Installing

KTrapIC is a [StationeersLaunchPad](https://github.com/StationeersLaunchPad/StationeersLaunchPad)
plugin mod: it needs BepInEx, StationeersLaunchPad and the IC10 Editor mod. Unzip a release's
`KTrapIC-<version>.zip` into StationeersLaunchPad's local mods directory
(`Documents/my games/Stationeers/mods`), which gives `mods/KTrapIC/KTrapIC.dll` and
`mods/KTrapIC/About/`.

The plugin doesn't include the compiler. The first time a Kotlin program is edited it downloads,
from this repository's GitHub release for its version, the compiler (`ktrapic-daemon.jar`) and a
trimmed Java 21 runtime to run it on (`ktrapic-runtime-windows-x64.zip`), about 110 MB in all,
into `BepInEx/cache/ktrapic`. It checks the release again each time it starts the compiler, and downloads
again only if the release's files have changed. A Release build of the plugin uses the release
tagged with its version (`v0.1.0`); a Debug build uses the rolling `dev` release, which every push
to `main` replaces. No version has been tagged yet, so for now install from the
[`dev` release](https://github.com/subitan/stationeers-ktrapic/releases/tag/dev), whose zip,
`KTrapIC-main.zip`, is a Debug build. Only Windows x64 has a runtime release; elsewhere, set both
`JavaPath` and `DaemonJarPath` (below).

## Using it

In the IC10 Editor, pick "Kotlin" as the language. Kotlin keywords and the SDK's names are highlighted:
types and prefabs (`GrowLights`, `Batteries.Small`), enums and their values (`Color.Red`), logic
types (`d0.Temperature`), devices and functions. A program is compiled as it is edited, about a
moment after typing stops:

- The IC10 it compiles to is shown in a read-only editor beside it, or the first error, with its
  line and column.
- The compiler's errors are marked in the code, in red: hovering over one, or putting the caret
  on it, shows the message. Warnings aren't marked, as the IC10 Editor shows errors only.
- The toolbar shows the IC10's lines, bytes and registers. The editor's size limits (128 lines,
  4096 bytes) apply to the IC10, not to the Kotlin.
- The IC10 lines that the caret's line compiled to are highlighted.
- "Compact" resolves labels to line numbers and drops them, to save lines.

Exporting writes the IC10 to the chip, followed by the Kotlin source, compressed and encoded in
comment lines that the chip skips. Opening the chip again shows the Kotlin. A program that doesn't
compile exports as a comment saying why, with the source kept the same way, so the chip runs
nothing. Chips run plain IC10, so they keep working without the mod; the game's own editor then
shows the encoded source as comments.

The IC10 Editor picks the language of code it opens by itself: a chip exported from here, and a
program whose first line is `import ktrapic.*`, open as Kotlin.

The console command `ktrapic` shows the compiler's status (`ktrapic status`), restarts it
(`ktrapic restart`), downloads it again (`ktrapic reinstall`), or shows the versions
(`ktrapic version`).

### Settings

In `BepInEx/config/com.github.subitan.stationeers-ktrapic.cfg` (or StationeersLaunchPad's
settings):

- `General.Compact`: the Compact toggle, remembered.
- `Development.JavaPath`: a java executable (21 or later) to use instead of the downloaded
  runtime.
- `Development.DaemonJarPath`: a compiler jar to use instead of the downloaded one, such as a
  local build of `//daemon:ktrapic-daemon_deploy.jar`.

### How the plugin talks to the compiler

The compiler is the compile daemon (`daemon/` in this repository), run as one java process for
all the editor's tabs: `java <JVM options> -jar ktrapic-daemon.jar --lsp`, with no console
window. The plugin starts it the first time a Kotlin program is edited, again with the next edit
if it stops, and on `ktrapic restart`. It speaks the Language Server Protocol to it over the
process's standard input and output (JSON-RPC with `Content-Length` framing, UTF-8):

- Each editor's program is a document under a URI of its own, `ktrapic:///<n>/<file name>.kt`.
  Edits are sent as they're made (`textDocument/didOpen`, then incremental
  `textDocument/didChange`), in the order they're made, by one writer thread, so the game never
  waits on the pipe.
- The daemon compiles each version for its errors and publishes them
  (`textDocument/publishDiagnostics`). The daemon publishes every document's on the one
  connection, so each editor applies only its own document's, and only if they're of the
  version it shows.
- The preview, the toolbar and the export use the custom request `ktrapic/compile`, sent on the
  same connection after the edits it compiles. An export waits for it on the main thread, up to
  30 seconds, but never for the compiler to start: until it has, an export writes a comment
  saying so.
- When the daemon starts again (`ktrapic restart`, or after it stopped), each editor opens its
  document on it again, with its whole text, and compiles it.

The plugin uses its own client for this (`LspConnection`), under the IC10 Editor's LSP formatter,
rather than the editor's stdio client, which would start a process of its own and can only restart
it in game. The daemon's JVM options keep it small beside the game:

- `-Xmx256m` caps the heap. Uncapped, the JVM may grow it to a quarter of the RAM: on a 32 GB
  machine, a series of compiles (40 of a 66-line program and one of a 5,000-line one) peaked at
  1.16 GB of working set with the default collector, 850 MB with the serial one, and 560 MB with
  this cap, with at most 110 MB of the heap still in use after a full collection. Warm compiles
  took 169 ms with these options, against 146 ms with the serial collector uncapped and 135 ms
  with the defaults (one run of each).
- `-XX:+UseSerialGC`: one collector thread and less bookkeeping than the default G1. Its pauses
  don't matter for a compiler.
- `-XX:+ExitOnOutOfMemoryError`: a daemon that runs out of memory exits, rather than carrying on
  in a broken state, and starts again with the next edit.
- `-XX:+DisplayVMOutputToStderr`: the JVM's own messages, such as the reason for that exit, go to
  standard error, so they reach the BepInEx log, rather than to standard output, which carries the
  protocol.
- `-Dstderr.encoding=UTF-8`: its log, which goes to the BepInEx log as `[compiler] ...` lines.

## Not yet

- Library imports from the IC10 Editor's Library window.
- Completion and hover information.
- Device data for prefabs added by other mods.

## Building

The plugin is a C# project (`KTrapIC.csproj`, .NET Framework 4.8), built with the .NET SDK (10 or
later), separately from the Bazel build. It compiles against the game's DLLs, found from (in
order) `StationeersDirectory` in `mod/Local.Developer.props`, the `STATIONEERS_DIR` environment
variable, or the default Steam locations. For example, a `mod/Local.Developer.props` (untracked)
of:

```xml
<Project>
  <PropertyGroup>
    <StationeersDirectory>D:/SteamLibrary/steamapps/common/Stationeers</StationeersDirectory>
    <StationeersModDir>C:/Users/you/Documents/my games/Stationeers/mods</StationeersModDir>
  </PropertyGroup>
</Project>
```

Then, from `mod/`:

```
dotnet build KTrapIC.csproj              # bin/Debug/net48/KTrapIC.dll
dotnet build KTrapIC.csproj -t:Install   # and copy it, with About/, into StationeersModDir/KTrapIC
dotnet build -c Release KTrapIC.csproj -t:Dist   # the release layout, in dist/KTrapIC
```

The first build downloads the IC10 Editor release it compiles against into `mod/deps`. A CI build
(`GITHUB_ACTIONS=true`) uses the stand-in game DLLs of the `mod/Template` submodule
([stationeers-modding-template](https://github.com/aproposmath/stationeers-modding-template)), which
needs `git submodule update --init` and Git LFS.

To try a local compiler in game, build it (`bazel build //daemon:ktrapic-daemon_deploy.jar`) and
set `DaemonJarPath` to it, and `JavaPath` to a JDK 21's `bin/java.exe`.

### Tests

`mod/tests` tests the plugin's game-independent code: its client of the compile daemon
(`LspConnection`, `LspDocument`) against a real daemon, started with the plugin's JVM options
(`LspDocumentTest.cs`: compiles for the preview and the export, edits and compiles in order, each
document's diagnostics reaching only it, documents opened again on a daemon started again), the
daemon's LSP mode message by message through that client (`DaemonLspTest.cs`), and the
highlighting's name lookup. What needs the game, the editor's formatter itself, isn't tested
here.

```
bazel build //daemon:ktrapic-daemon_deploy.jar
KTRAPIC_DAEMON_JAR="$PWD/bazel-bin/daemon/ktrapic-daemon_deploy.jar" JAVA_HOME=<a JDK 21> dotnet test mod/tests
```

`KTRAPIC_DAEMON_JAR` must be an absolute path: the tests run in their build output directory.

CI runs them with `JAVA_HOME` set to the trimmed runtime that `mod/package-runtime.sh` builds,
which checks that the runtime has every module the compiler needs.

### Releases

`.github/workflows/release.yml` builds the plugin, the compiler jar and the Windows runtime, and
publishes them: to the `dev` prerelease on each push to `main`, and to a release on a tag `vX.Y.Z`,
which must match `About/About.xml`'s version.
