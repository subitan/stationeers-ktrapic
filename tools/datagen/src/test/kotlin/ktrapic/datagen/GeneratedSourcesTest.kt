package ktrapic.datagen

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.jupiter.api.Test

class GeneratedSourcesTest {
  private val workspace = File(System.getenv("TEST_SRCDIR"), System.getenv("TEST_WORKSPACE"))

  @Test
  fun `the committed runtime sources match the data`() {
    for ((name, text) in DataFiles.generate(workspace)) {
      val committed = DataFiles.generatedDir(workspace).resolve(name).readText()
      assertWithMessage("%s is stale; regenerate with: bazel run //tools/datagen -- generate", name)
          .that(committed.replace("\r\n", "\n"))
          .isEqualTo(text)
    }
  }
}
