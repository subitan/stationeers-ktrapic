# KTrapIC for IntelliJ IDEA

An IntelliJ IDEA plugin for KTrapIC programs (`*.ic10.kts`), for IDEA 2025.1 and later. It
bundles everything it needs: the compile daemon, run on the IDE's own Java, and the SDK and script
definition. It gives:

- **Kotlin completion and type checking** against the `ktrapic` API, with `import ktrapic.*`
  implied: the plugin hands KTrapIC's script definition to the Kotlin plugin, which then checks
  programs as Kotlin, with the API's KDoc in hovers. If the Kotlin plugin offers more than one
  definition for a file, pick "KTrapIC program" under Settings > Languages & Frameworks > Kotlin >
  Kotlin Scripting.
- **KTrapIC's errors and warnings in the editor**, as you type: unsupported constructs, register
  and size limits, constant overflow and the like, prefixed `KTrapIC:`. The Kotlin compiler's
  own problems are left to the Kotlin plugin, which shows them already; a setting shows them too.
  When those are the only reason a program doesn't compile, one `KTrapIC: doesn't compile: ...`
  error at the top of the editor names the first of them, as the daemon compiles the program
  wrapped in an object and a few of its errors (`'return' is prohibited here`, say) are ones the
  Kotlin plugin doesn't see in the script. A problem with no position in the source (a program
  that needs too many registers, say) is shown at the top of the editor too.
- **The IC10 tool window** (View > Tool Windows > IC10): the selected program compiled to IC10,
  with its lines, bytes and registers against the chip's 128 lines, 4096 bytes
  and 16 registers, those over the limit marked, and the compiler's warnings (or, when it doesn't
  compile, its errors). Its toolbar copies the IC10, and restarts the
  compile daemon (after rebuilding its jar, say).
- **Settings** under Settings > Tools > KTrapIC: the compile daemon's jar, the Java that runs it,
  and whether to show the Kotlin compiler's own problems too. There are no compile options: a
  program says how it compiles with file annotations (`@file:Compact`, `@file:InRegisters`,
  `@file:NoTailCalls`; see docs/LANGUAGE.md), so it compiles the same in the IDE, the playground and
  the game.

## How it compiles

The plugin runs KTrapIC's compile daemon (`daemon/`) as a separate Java process, one per open
project, and speaks its line protocol: one line of base64-encoded JSON per request and per response
(see `daemon/src/main/kotlin/ktrapic/daemon/Daemon.kt`). The daemon bundles its own Kotlin compiler,
which would clash with the IDE's if loaded into it. The daemon starts on the first compile, restarts
on the next compile if it exits, and stops when the project closes. Its standard error goes to the
IDE's log (Help > Show Log).

The daemon is the one the plugin bundles, unless the daemon jar setting names another, or the open
project is a checkout of this repository with the daemon built in `bazel-bin`, which then wins, as
the daemon being worked on.

The daemon runs from a copy of its jar, so the jar can be rebuilt (or the plugin updated) while it
runs: Windows keeps a running jar locked. The copies go in `ktrapic-daemon-jars/` beside the IDE's
system directory, shared by every IDE version, when the system directory is in the usual
`JetBrains` directory (`%LOCALAPPDATA%\JetBrains`, `~/.cache/JetBrains`,
`~/Library/Caches/JetBrains`), and in the system directory's `ktrapic/daemon-jars/` otherwise. Copies are named by the
jar's name, size and modification time, and projects running the same jar share one. A rebuilt jar
is copied and run on the next compile, once the copy is a complete jar. When a daemon starts, every
other copy not made or used in the last ten minutes is deleted, for every project (a newer one may
be one another project is starting a daemon from): on Windows a copy another project's daemon still
runs from can't be, and goes when a later daemon starts; elsewhere that daemon goes on running from
the deleted file, and its project copies the jar again when it next needs it. While the jar can't be
copied (Bazel deletes it and then writes it in place; `bazel clean` deletes it), the daemon already
running from a copy of it goes on compiling, and the jar is copied again on the next compile.

A compile the daemon gave no answer for (it isn't set up, it couldn't start, it exited or it timed
out) isn't kept: asking again for the same text compiles it again.

The editor's annotator compiles a program in the background after each change; the IC10 tool window
shows that compile, or compiles the program itself a moment after a change when the annotator hasn't
(in power save mode, say). Both share one compile of the same text.

The daemon tells the Kotlin compiler's own problems from KTrapIC's by marking them
`"kotlin": true`.

## Building and installing

The plugin builds with Gradle (the IntelliJ Platform Gradle Plugin), not Bazel, and needs JDK 21.
It bundles the daemon jar, built with Bazel:

```
bazel build //daemon:ktrapic-daemon_deploy.jar
cd intellij
./gradlew buildPlugin
```

The build takes the daemon jar from the Gradle property `ktrapicDaemonJar`, or else
`$KTRAPIC_DAEMON_JAR`, or else `../bazel-bin/daemon/ktrapic-daemon_deploy.jar`, and fails without
one. It compiles the SDK and script definition itself, from `../sdk`, for the Kotlin that IntelliJ
2025.1 bundles (2.1): the Kotlin plugin there can't read the Bazel build's classes, which are
compiled for Kotlin 2.4. The plugin's directory has the daemon in `daemon/ktrapic-daemon.jar` and
the SDK and script definition in `script/`, none of them on the plugin's own classpath.

The plugin is `build/distributions/ktrapic-intellij-<version>.zip`, about 60 MB, nearly all of it
the daemon. Install it with Settings > Plugins > the gear menu > Install Plugin from Disk, and
restart the IDE. With the Java setting blank, the daemon runs on the IDE's own Java runtime.
[docs/RELEASING.md](../docs/RELEASING.md) covers releases to JetBrains Marketplace.

Gradle downloads IntelliJ IDEA Community 2025.1 to build against into its user home: about
1.3 GB, and 3.5 GB more once extracted. To keep it off a full system drive, set `GRADLE_USER_HOME`, and
`org.jetbrains.intellij.platform.intellijPlatformCache` in that directory's `gradle.properties`
(it defaults to `intellij/.intellijPlatform`).

## Tests

```
./gradlew test
```

runs the plugin's tests on the platform's test framework. The daemon client's tests run against the
real daemon when `KTRAPIC_DAEMON_JAR` is the absolute path of a built
`ktrapic-daemon_deploy.jar`, and are skipped otherwise. CI builds the jar and runs them.

## Not yet

Settings are per IDE, not per project. The tests check that programs get the bundled
script definition, compiling against the bundled SDK, but not the Kotlin plugin's highlighting of
a program with it, which loads in the background in a way the test framework doesn't run.
