package ktrapic.intellij

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The compile service shares compiles of the same text and skips those replaced by newer ones. */
class KtrapicCompileServiceTest : BasePlatformTestCase() {
  private val compiled = mutableListOf<String>()
  private val release = CountDownLatch(1)
  private lateinit var service: KtrapicCompileService

  override fun setUp() {
    super.setUp()
    service = KtrapicCompileService.getInstance(project)
    service.compilerForTests = Ic10Compiler { code, _ ->
      synchronized(compiled) { compiled += code }
      release.await(30, TimeUnit.SECONDS)
      CompileResponse.Success(code, 1, code.length, 0, emptyList())
    }
  }

  override fun tearDown() {
    try {
      release.countDown()
      service.compilerForTests = null
      service.restart()
    } finally {
      super.tearDown()
    }
  }

  private fun request(text: String) = CompileRequest("/program.ic10.kts", text, CompileOptions())

  fun `test the same request shares one compile`() {
    val first = service.compile(request("a"))
    val second = service.compile(request("a"))
    release.countDown()

    assertSame(first, second)
    assertEquals("a", (first.get(30, TimeUnit.SECONDS) as CompileResponse.Success).code)
    assertEquals(listOf("a"), synchronized(compiled) { compiled.toList() })
    assertEquals(request("a"), service.lastCompiled("/program.ic10.kts")?.request)
  }

  fun `test a queued compile replaced by a newer one is skipped`() {
    val running = service.compile(request("a"))
    // Wait for "a" to start, so "b" queues behind it and "c" replaces "b".
    while (synchronized(compiled) { compiled.isEmpty() }) Thread.sleep(10)
    val replaced = service.compile(request("b"))
    val latest = service.compile(request("c"))
    release.countDown()

    latest.get(30, TimeUnit.SECONDS)
    assertNull(service.await(replaced) {})
    assertNotNull(running.get(30, TimeUnit.SECONDS))
    assertEquals(listOf("a", "c"), synchronized(compiled) { compiled.toList() })
  }

  fun `test a compile the daemon didn't answer is compiled again`() {
    service.compilerForTests = Ic10Compiler { _, _ ->
      CompileResponse.Failure("No KTrapIC compile daemon", answered = false)
    }
    val first = service.compile(request("a")).get(30, TimeUnit.SECONDS)
    assertNull(service.lastCompiled("/program.ic10.kts"))

    service.compilerForTests = Ic10Compiler { code, _ ->
      CompileResponse.Success(code, 1, code.length, 0, emptyList())
    }
    val second = service.compile(request("a")).get(30, TimeUnit.SECONDS)

    assertFalse((first as CompileResponse.Failure).answered)
    assertEquals("a", (second as CompileResponse.Success).code)
    assertEquals(second, service.lastCompiled("/program.ic10.kts")?.response)
  }

  fun `test a compile the daemon answered with errors is shared`() {
    val calls = mutableListOf<String>()
    service.compilerForTests = Ic10Compiler { code, _ ->
      synchronized(calls) { calls += code }
      CompileResponse.Failure("Unsupported")
    }
    val first = service.compile(request("a"))
    first.get(30, TimeUnit.SECONDS)

    assertSame(first, service.compile(request("a")))
    assertEquals(listOf("a"), synchronized(calls) { calls.toList() })
  }
}
