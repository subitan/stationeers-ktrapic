package ktrapic.daemon

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.util.zip.ZipFile
import org.junit.jupiter.api.Test

class NoticesTest {
  private val workspace = File(System.getenv("TEST_SRCDIR"), System.getenv("TEST_WORKSPACE"))

  @Test
  fun `the deploy jar ships KTrapIC's and PyTrapIC's license notices`() {
    ZipFile(workspace.resolve("daemon/ktrapic-daemon_deploy.jar")).use { jar ->
      for ((entry, source) in
          listOf(
              "META-INF/ktrapic/LICENSE" to "LICENSE",
              "META-INF/ktrapic/PYTRAPIC-LICENSE" to "sdk/data/PYTRAPIC-LICENSE",
          )) {
        val shipped = jar.getEntry(entry)?.let { jar.getInputStream(it).use { it.readBytes() } }
        assertWithMessage("%s in the deploy jar", entry)
            .that(shipped)
            .isEqualTo(workspace.resolve(source).readBytes())
      }
    }
  }
}
