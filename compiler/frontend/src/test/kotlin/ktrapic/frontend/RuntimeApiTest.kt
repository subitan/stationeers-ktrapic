package ktrapic.frontend

import com.google.common.truth.Truth.assertThat
import java.io.File
import ktrapic.Device
import org.jetbrains.kotlin.ir.util.dump
import org.junit.jupiter.api.Test

class RuntimeApiTest {
  private val runtimeJar = File(Device::class.java.protectionDomain.codeSource.location.toURI())

  private fun compile(source: String) =
      K2Frontend.compile("import ktrapic.*\n" + source, listOf(runtimeJar)) { it.dump() }

  private fun errors(result: FrontendResult<*>) =
      result.diagnostics.filter { it.severity == Diagnostic.Severity.ERROR }

  @Test
  fun `a program using devices, logic types and intrinsics type-checks`() {
    val result =
        compile(
            """
            d0.Vertical = 45.0
            d0.On = true
            while (d0.SignalStrength < 97.0) {
              d2.Setting = d0[LogicType.SignalStrength]
              db[LogicType.Setting] = Color.Red
              sleep(1)
              sleep(0.25)
              yield()
            }
            d5.Setting = str("Done!") + hash("StructureBattery")
            """
                .trimIndent()
        )

    assertThat(errors(result)).isEmpty()
    val dump = (result as FrontendResult.Success).value
    assertThat(dump).contains("fun <set-Vertical> (value: kotlin.Double)")
    // Game values are constants, which reach the IR already folded.
    assertThat(dump).contains("CONST Double type=kotlin.Double value=4.0")
  }

  @Test
  fun `a Boolean logic type rejects a number`() {
    val result = compile("d0.On = 1.0\n")

    assertThat(errors(result)).hasSize(1)
    assertThat(errors(result).single().line).isEqualTo(2)
  }

  @Test
  fun `a program cannot make its own devices`() {
    for (source in listOf("val d = Device()\n", "class Mine : GenericDevice()\n")) {
      val error = errors(compile(source)).single()
      assertThat(error.message).contains("it is internal in")
    }
  }
}
