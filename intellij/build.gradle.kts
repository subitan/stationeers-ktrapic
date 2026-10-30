import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
  id("org.jetbrains.kotlin.jvm") version "2.3.21"
  id("org.jetbrains.intellij.platform") version "2.19.0"
  id("org.jetbrains.changelog") version "2.4.0"
}

group = "ktrapic"

version = providers.gradleProperty("pluginVersion").get()

// The Kotlin that IntelliJ 2025.1 bundles, which the plugin runs against. Its Kotlin analyzer can't
// read classes compiled for a much newer Kotlin, such as the Bazel build's (2.4), so the plugin
// builds its own copy of the SDK and script definition for this one.
val ideKotlin = "2.1.20"

repositories {
  mavenCentral()
  intellijPlatform { defaultRepositories() }
}

// The SDK (the ktrapic API that programs compile against) and the *.ic10.kts script definition,
// from the repository's sources, for the IDE's Kotlin plugin to check programs against. The plugin
// bundles them (see the sandbox below) rather than putting them on its own classpath.
val sdk: SourceSet =
    sourceSets.create("sdk") { kotlin.srcDir(rootProject.file("../sdk/src/main/kotlin")) }
val script: SourceSet =
    sourceSets.create("script") {
      kotlin.srcDir(rootProject.file("../sdk/script/src/main/kotlin"))
      resources.srcDir(rootProject.file("../sdk/script/src/main/resources"))
      compileClasspath += sdk.output
    }

dependencies {
  intellijPlatform {
    create(IntelliJPlatformType.IntellijIdeaCommunity, "2025.1")
    bundledPlugin("org.jetbrains.kotlin")
    testFramework(TestFrameworkType.Platform)
  }
  "sdkCompileOnly"("org.jetbrains.kotlin:kotlin-stdlib:$ideKotlin")
  "scriptCompileOnly"("org.jetbrains.kotlin:kotlin-stdlib:$ideKotlin")
  "scriptCompileOnly"("org.jetbrains.kotlin:kotlin-scripting-common:$ideKotlin")
  "scriptCompileOnly"("org.jetbrains.kotlin:kotlin-scripting-jvm:$ideKotlin")
  testImplementation("junit:junit:4.13.2")
  testImplementation("org.opentest4j:opentest4j:1.3.0")
}

kotlin {
  jvmToolchain(21)
  compilerOptions {
    jvmTarget = JvmTarget.JVM_21
    // IntelliJ 2025.1 bundles Kotlin 2.1's standard library, which the plugin runs against.
    apiVersion = KotlinVersion.KOTLIN_2_1
    languageVersion = KotlinVersion.KOTLIN_2_1
  }
}

intellijPlatform {
  pluginConfiguration {
    version = project.version.toString()
    // The release's section of CHANGELOG.md, or Unreleased's before the release.
    changeNotes = provider {
      with(changelog) {
        renderItem(
            (getOrNull(project.version.toString()) ?: getUnreleased())
                .withHeader(false)
                .withEmptySections(false),
            Changelog.OutputType.HTML,
        )
      }
    }
    ideaVersion {
      sinceBuild = "251"
      untilBuild = provider { null }
    }
  }
  // The plugin has a single settings page; indexing its options would start an IDE at build time.
  buildSearchableOptions = false
  // Nothing to instrument: no GUI forms, and Kotlin checks its own nullability.
  instrumentCode = false

  // Releases (see docs/RELEASING.md) sign the plugin and publish it to JetBrains Marketplace with
  // these, which the release workflow has as secrets. Building and testing need none of them.
  signing {
    certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
    privateKey = providers.environmentVariable("PRIVATE_KEY")
    password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
  }
  publishing {
    token = providers.environmentVariable("PUBLISH_TOKEN")
    // 0.2.0 goes to the default (stable) channel, 0.2.0-beta.1 to "beta".
    channels =
        providers.gradleProperty("pluginVersion").map {
          listOf(it.substringAfter('-', "").substringBefore('.').ifEmpty { "default" })
        }
  }
  // The Plugin Verifier checks the plugin against the IDE releases JetBrains recommends for its
  // range of builds, as Marketplace does on upload.
  pluginVerification { ides { recommended() } }
}

// Named as the Bazel build names them: the script definition finds the SDK by the name `sdk`.
val sdkJar =
    tasks.register<Jar>("sdkJar") {
      archiveFileName = "sdk.jar"
      destinationDirectory = layout.buildDirectory.dir("bundled")
      from(sdk.output)
      // The sources too, for the KDoc that hovers in the editor show, as in the Bazel build.
      from(sdk.kotlin.srcDirs)
      // The platform's Gradle plugin adds plugin.xml to every source set's resources.
      exclude("META-INF/plugin.xml")
    }
val scriptJar =
    tasks.register<Jar>("scriptJar") {
      archiveFileName = "ktrapic-script.jar"
      destinationDirectory = layout.buildDirectory.dir("bundled")
      from(script.output)
      exclude("META-INF/plugin.xml")
    }

// The compile daemon's deploy jar, built with Bazel: the Gradle property ktrapicDaemonJar, or else
// $KTRAPIC_DAEMON_JAR, or else the one in this checkout's bazel-bin.
val daemonJar: Provider<RegularFile> =
    providers
        .gradleProperty("ktrapicDaemonJar")
        .orElse(providers.environmentVariable("KTRAPIC_DAEMON_JAR"))
        .orElse(rootProject.file("../bazel-bin/daemon/ktrapic-daemon_deploy.jar").absolutePath)
        .map { layout.projectDirectory.file(it) }

// Beside its own lib/, the plugin's directory holds daemon/ktrapic-daemon.jar, the compile daemon,
// run as a separate process, and script/, the SDK and script definition, which the Kotlin plugin
// loads (see KtrapicScriptDefinitionsSource). Neither is on the plugin's classpath: the daemon
// bundles a Kotlin compiler that would clash with the IDE's.
tasks.withType<PrepareSandboxTask>().configureEach {
  val jar = daemonJar
  doFirst {
    check(jar.get().asFile.isFile) {
      "No compile daemon jar at ${jar.get().asFile}: build it with " +
          "bazel build //daemon:ktrapic-daemon_deploy.jar, or name it with -PktrapicDaemonJar=..."
    }
  }
  from(jar) {
    into(pluginName.map { "$it/daemon" })
    rename { "ktrapic-daemon.jar" }
  }
  from(listOf(sdkJar, scriptJar)) { into(pluginName.map { "$it/script" }) }
}

changelog {
  groups = listOf("Added", "Changed", "Fixed", "Removed")
  repositoryUrl = "https://github.com/subitan/stationeers-ktrapic"
  // Release tags are intellij-vX.Y.Z, apart from the IC10 Editor plugin's vX.Y.Z.
  versionPrefix = "intellij-v"
}

tasks.test {
  // The platform's test framework makes temporary projects; keep them in the build directory
  // rather than the system temporary directory.
  val tmp = layout.buildDirectory.dir("tmp/test-tmp").get().asFile
  // On Windows the platform keeps data shared by all JetBrains IDEs (a device ID) under APPDATA;
  // the tests' copy goes in the build directory too, leaving the user's IDEs' alone.
  val appData = layout.buildDirectory.dir("tmp/test-appdata").get().asFile
  doFirst {
    tmp.mkdirs()
    appData.mkdirs()
  }
  systemProperty("java.io.tmpdir", tmp.absolutePath)
  environment("APPDATA", appData.resolve("Roaming").absolutePath)
  environment("LOCALAPPDATA", appData.resolve("Local").absolutePath)
}
