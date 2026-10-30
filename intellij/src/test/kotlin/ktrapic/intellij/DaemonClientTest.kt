package ktrapic.intellij

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The daemon client against the real daemon: the jar given by KTRAPIC_DAEMON_JAR (an absolute
 * path), run with the test's own Java. Skipped when KTRAPIC_DAEMON_JAR isn't set.
 */
class DaemonClientTest {
  private lateinit var client: DaemonClient

  @Before
  fun start() {
    val jar = System.getenv("KTRAPIC_DAEMON_JAR")
    assumeTrue("Set KTRAPIC_DAEMON_JAR to run the daemon client's tests", !jar.isNullOrBlank())
    check(File(jar).isAbsolute && File(jar).isFile) {
      "KTRAPIC_DAEMON_JAR must be the absolute path of a jar that exists: $jar"
    }
    client = DaemonClient(listOf(KtrapicPaths.java(null), "-jar", jar))
  }

  @After
  fun stop() {
    if (::client.isInitialized) client.close()
  }

  @Test
  fun `compiles a program`() {
    val response = client.compile("db.Setting = 1.0\n")

    assertEquals(CompileResponse.Success("s db Setting 1", 1, 14, 0, emptyList()), response)
  }

  @Test
  fun `compiles as the program's annotations say`() {
    val source = "var i = 0\nwhile (i < 3) i++\ndb.Setting = i.toDouble()\n"

    val labelled = client.compile(source) as CompileResponse.Success
    val compact = client.compile("@file:Compact\n$source") as CompileResponse.Success

    assertTrue(labelled.code.contains(":"))
    assertFalse(compact.code.contains(":"))
  }

  @Test
  fun `tells the Kotlin compiler's errors from KTrapIC's`() {
    val kotlin = client.compile("val x: Int = \"s\"\n") as CompileResponse.Failure
    val own = client.compile("val (a, b) = Pair(1, 2)\n") as CompileResponse.Failure

    assertTrue(kotlin.errors.all { it.kotlin })
    assertEquals(1, kotlin.errors.first().line)
    assertEquals(listOf(false), own.errors.map { it.kotlin })
  }

  @Test
  fun `restarts the daemon after it exits`() {
    client.compile("db.Setting = 1.0\n")
    val first = client.processForTests!!
    first.destroy()
    assertTrue(first.waitFor(30, TimeUnit.SECONDS))

    val response = client.compile("db.Setting = 2.0\n")

    assertEquals("s db Setting 2", (response as CompileResponse.Success).code)
    assertTrue(client.processForTests!!.isAlive)
  }

  @Test
  fun `fails once closed`() {
    client.close()

    assertTrue(client.compile("db.Setting = 1.0\n") is CompileResponse.Failure)
  }
}
