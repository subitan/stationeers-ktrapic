package ktrapic.datagen

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.jupiter.api.Test

class GeneratedSourcesTest {
  private val workspace = File(System.getenv("TEST_SRCDIR"), System.getenv("TEST_WORKSPACE"))

  @Test
  fun `the committed SDK sources match the data`() {
    for ((name, text) in DataFiles.generate(workspace)) {
      val committed = DataFiles.generatedDir(workspace).resolve(name).readText()
      assertWithMessage("%s is stale; regenerate with: bazel run //tools/datagen -- generate", name)
          .that(committed.replace("\r\n", "\n"))
          .isEqualTo(text)
    }
  }

  @Test
  fun `the plugin's list of names matches the generated sources`() {
    val expected = DataFiles.generateModNames(workspace, DataFiles.generate(workspace))
    assertWithMessage(
            "%s is stale; regenerate with: bazel run //tools/datagen -- generate",
            ModNamesGenerator.PATH,
        )
        .that(DataFiles.modNames(workspace).readText().replace("\r\n", "\n"))
        .isEqualTo(expected)
  }

  @Test
  fun `the device reference matches the generated sources`() {
    val expected = DataFiles.generateDeviceReference(workspace, DataFiles.generate(workspace))
    assertWithMessage(
            "%s is stale; regenerate with: bazel run //tools/datagen -- generate",
            DeviceReferenceGenerator.PATH,
        )
        .that(DataFiles.deviceReference(workspace).readText().replace("\r\n", "\n"))
        .isEqualTo(expected)
  }
}
