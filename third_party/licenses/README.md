# Third-party license notices

The compile daemon's deploy jar (`//daemon:ktrapic-daemon_deploy.jar`, shipped as
`ktrapic-daemon.jar`) bundles the libraries below. This directory holds their license and notice
files, copied unchanged from each project's own repository at the version the jar bundles (two
files had their CRLF line endings converted to LF; see below). The jar carries the whole directory,
this README included, under `META-INF/ktrapic/third-party/`. KTrapIC's own license and PyTrapIC's
are next to it in `META-INF/ktrapic/`.

`daemon/src/test/kotlin/ktrapic/daemon/ThirdPartyNoticesTest.kt` maps every package in the jar to
the files here that cover it, and fails when the jar gains a package that no entry covers, or when
the jar lacks a file that an entry names or that `kotlin/README.md` links to.

| Directory | Component (version) | Packages in the jar | Source |
|---|---|---|---|
| `kotlin/` | Kotlin compiler, standard library, reflection, scripting, build-tools API, daemon client (2.4.21; kotlin-reflect 1.6.10, as kotlin-compiler-embeddable 2.4.21 depends on it), all Apache-2.0 | `kotlin/`, `org/jetbrains/kotlin/` (except the shaded and derived code below) | Kotlin's `license/` set at tag v2.4.21: https://github.com/JetBrains/kotlin/tree/v2.4.21/license |
| `kotlin/third_party/` | Libraries shaded into the Kotlin compiler that Kotlin's set covers: fastutil 8.5.14-jb1, Guava 33.3.1-jre, protobuf 2.6.1 (relocated), Aalto XML 1.3.0, Stax2 API 4.2.1, OpenTelemetry API 1.41.0 | see the test | as above |
| `kotlin-reflect/` | Code in kotlin-reflect 1.6.10 derived from PCollections, MIT (Kotlin's v2.4.21 set does not list it) | `kotlin/reflect/jvm/internal/pcollections/` | `pcollections_LICENSE.txt` from Kotlin's `license/third_party/` at tag v1.6.10: https://github.com/JetBrains/kotlin/blob/v1.6.10/license/third_party/pcollections_LICENSE.txt |
| `intellij/` | IntelliJ Platform core, shaded into the Kotlin compiler (build 251.27812.49), Apache-2.0 | `org/jetbrains/kotlin/com/intellij/`, `org/jetbrains/concurrency/`, `messages/`, `misc/` | https://github.com/JetBrains/intellij-community/tree/idea/251.27812.49 (`LICENSE.txt`, `NOTICE.txt`) |
| `intellij/javolution_license.txt` | IntelliJ's `ImmutableText`, a pruned version of Javolution's `javolution.text.Text` (IntelliJ `platform/util/base`), BSD-2-Clause | `org/jetbrains/kotlin/com/intellij/util/text/ImmutableText` and its nested classes | `license/javolution_license.txt` of IntelliJ at the same tag: https://github.com/JetBrains/intellij-community/blob/idea/251.27812.49/license/javolution_license.txt (the same text as the class's source header) |
| `pluralize/` | IntelliJ's `Pluralizer`, from Blake Embrey's pluralize (IntelliJ `platform/util/base`), MIT | `org/jetbrains/kotlin/com/intellij/openapi/util/text/Pluralizer` | https://github.com/blakeembrey/pluralize/blob/v8.0.0/LICENSE (the text the class's source header quotes; IntelliJ's `license/` has no file for it) |
| `asm/` | ASM 9.6.1 (IntelliJ's `asm-all` build), BSD-3-Clause | `org/jetbrains/org/objectweb/asm/` | https://gitlab.ow2.org/asm/asm/-/raw/ASM_9_6/LICENSE.txt (no ASM_9_6_1 tag exists) |
| `jdom/` | JDOM, IntelliJ's fork (util-jdom 251.27812.49), JDOM license | `org/jetbrains/kotlin/org/jdom/` | https://github.com/hunterhacker/jdom/blob/JDOM-2.0.6.1/LICENSE.txt (the fork's sources carry the same text, copyright 2000-2012) |
| `picocontainer/` | PicoContainer interfaces, in IntelliJ core, BSD-3-Clause | `org/jetbrains/kotlin/org/picocontainer/` | https://github.com/picocontainer/PicoContainer1/blob/picocontainer-1.2/LICENSE.txt |
| `antlr4/` | ANTLR 4 runtime 4.13.2, BSD-3-Clause | `org/jetbrains/kotlin/org/antlr/v4/` | https://github.com/antlr/antlr4/blob/4.13.2/LICENSE.txt |
| `log4j/` | log4j 1.2.17 (JetBrains' 1.2.17.2 build), Apache-2.0 | `org/jetbrains/kotlin/org/apache/log4j/` | https://github.com/apache/logging-log4j1/tree/v1_2_17 (`LICENSE`, `NOTICE`) |
| `jansi/` | Jansi 2.4.0, Apache-2.0 | `org/jetbrains/kotlin/org/fusesource/jansi/` | https://github.com/fusesource/jansi/blob/jansi-2.4.0/license.txt |
| `jline/` | JLine 3.30.16, BSD-3-Clause | `org/jetbrains/kotlin/org/jline/` | https://github.com/jline/jline3/blob/jline-3.30.16/LICENSE.txt |
| `javax-inject/` | javax.inject 1, Apache-2.0 | `org/jetbrains/kotlin/javax/inject/` | The project ships no license file (its `pom.xml` names Apache-2.0); the text is https://www.apache.org/licenses/LICENSE-2.0.txt |
| `kotlinx-collections-immutable/` | kotlinx.collections.immutable 0.5.1, Apache-2.0 | `org/jetbrains/kotlin/kotlinx/collections/immutable/` | https://github.com/Kotlin/kotlinx.collections.immutable/blob/v0.5.1/LICENSE.txt |
| `streamex/` | StreamEx 0.7.2, Apache-2.0 | `org/jetbrains/kotlin/one/util/streamex/` | https://github.com/amaembo/streamex/blob/streamex-0.7.2/LICENSE |
| `lz4-java/` | lz4-java 1.7.1, Apache-2.0, with its native libraries built from LZ4's `lib/` (`lz4.c`, `lz4hc.c` and LZ4's copy of xxHash, `xxhash.c`; BSD-2-Clause) | `org/jetbrains/kotlin/net/jpountz/` | https://github.com/lz4/lz4-java/blob/1.7.1/LICENSE.txt; `lz4-lib-LICENSE` is `lib/LICENSE` of LZ4 at the submodule commit lz4-java 1.7.1 pins: https://github.com/lz4/lz4/blob/fdf2ef5809ca875c454510610764d9125ef2ebbd/lib/LICENSE |
| `vavr/` | Vavr 0.10.4, Apache-2.0 | `org/jetbrains/kotlin/io/vavr/` | https://github.com/vavr-io/vavr/blob/v0.10.4/LICENSE |
| `kotlinx-coroutines/` | kotlinx.coroutines 1.8.0, Apache-2.0 | `kotlinx/coroutines/`, `_COROUTINE/`, `DebugProbesKt.bin` | https://github.com/Kotlin/kotlinx.coroutines/tree/1.8.0 (`LICENSE.txt`, `license/NOTICE.txt`) |
| `gson/` | Gson 2.8.9, Apache-2.0 | `com/google/gson/` | https://github.com/google/gson/blob/gson-parent-2.8.9/LICENSE |
| `jetbrains-annotations/` | JetBrains Java annotations 13.0, Apache-2.0 | `org/jetbrains/annotations/`, `org/intellij/lang/annotations/` | https://github.com/JetBrains/java-annotations/blob/16.0.1/LICENSE.txt (the repository's earliest tag; 13.0 predates it) |

Notes:

- Kotlin's `license/third_party/testdata/` is left out: Kotlin's README says that code "is not
  incorporated into any distributions", so the README's links into it are dangling here.
- `jdom/LICENSE.txt` and `streamex/LICENSE` had CRLF line endings upstream; they are stored with LF.
- Gson, Guava, fastutil, Aalto XML, Jansi, StreamEx, Vavr and the OpenTelemetry API have no NOTICE
  file at their repository roots at these versions.
- IntelliJ's `license/` directory at 251.27812.49 also has notices for JavaHelp, Saxon and YourKit.
  None of the IntelliJ modules whose classes the jar holds (`platform/util*`, `platform/core-*`,
  `platform/extensions`, `java/java-psi-*`, `java/java-frontback-*`) contains code from them. The
  other code in those modules under another project's copyright is under Apache-2.0 (fastutil's
  `Hash`, AOSP's `DisposableWrapperList`), which IntelliJ's `LICENSE.txt` covers.
  `ConcurrentIntObjectHashMap`, adapted from Doug Lea's public-domain `ConcurrentHashMap`, carries
  JetBrains' Apache-2.0 header.
- The versions of the libraries shaded into the Kotlin compiler come from Kotlin's build files at
  v2.4.21 (`gradle/libs.versions.toml`, `gradle/versions.properties`,
  `prepare/compiler/build.gradle.kts`); the others from `maven_install.json`.
