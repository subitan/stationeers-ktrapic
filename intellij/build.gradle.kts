import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
  id("org.jetbrains.kotlin.jvm") version "2.3.21"
  id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "ktrapic"

version = "0.1.0"

repositories {
  mavenCentral()
  intellijPlatform { defaultRepositories() }
}

dependencies {
  intellijPlatform {
    create(IntelliJPlatformType.IntellijIdeaCommunity, "2025.1")
    bundledPlugin("org.jetbrains.kotlin")
    testFramework(TestFrameworkType.Platform)
  }
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
    ideaVersion {
      sinceBuild = "251"
      untilBuild = provider { null }
    }
  }
  // The plugin has a single settings page; indexing its options would start an IDE at build time.
  buildSearchableOptions = false
  // Nothing to instrument: no GUI forms, and Kotlin checks its own nullability.
  instrumentCode = false
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
