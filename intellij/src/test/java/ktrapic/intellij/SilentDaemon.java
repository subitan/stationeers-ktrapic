package ktrapic.intellij;

import java.io.IOException;

/**
 * A stand-in daemon that reads its requests and never answers. It writes its process ID to
 * standard error once it is running.
 */
public final class SilentDaemon {
  private SilentDaemon() {}

  public static void main(String[] args) throws IOException {
    System.err.println(ProcessHandle.current().pid());
    System.err.flush();
    while (System.in.read() >= 0) {}
  }
}
