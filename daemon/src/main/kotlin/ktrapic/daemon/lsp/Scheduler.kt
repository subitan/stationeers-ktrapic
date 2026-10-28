package ktrapic.daemon.lsp

import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Runs [LspServer]'s debounced diagnostics compiles away from the thread that reads messages. */
internal interface Scheduler : AutoCloseable {
  /** Runs [task] after [delayMillis]; cancelling the result stops it if it hasn't started yet. */
  fun schedule(delayMillis: Long, task: Runnable): Future<*>

  /** Stops running tasks: those not started yet never run. */
  override fun close()
}

/** A [Scheduler] with one worker thread, a daemon thread so that it never keeps the JVM up. */
internal class WorkerScheduler : Scheduler {
  private val executor: ScheduledExecutorService =
      Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "ktrapic-diagnostics").apply { isDaemon = true }
      }

  override fun schedule(delayMillis: Long, task: Runnable): Future<*> =
      executor.schedule(task, delayMillis, TimeUnit.MILLISECONDS)

  override fun close() {
    executor.shutdownNow()
  }
}
