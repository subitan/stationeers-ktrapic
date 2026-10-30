package ktrapic.intellij;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * A stand-in daemon that answers every request with a program whose code is its process ID. It
 * writes its process ID to standard error once it is running. Its answer is made by {@link Answer},
 * which the JVM loads from the jar only on the first request.
 */
public final class PidDaemon {
  private PidDaemon() {}

  public static void main(String[] args) throws IOException {
    System.err.println(ProcessHandle.current().pid());
    System.err.flush();
    BufferedReader requests =
        new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    while (requests.readLine() != null) {
      System.out.println(Answer.line());
      System.out.flush();
    }
  }

  static final class Answer {
    private Answer() {}

    static String line() {
      String json = "{\"code\":\"" + ProcessHandle.current().pid() + "\"}";
      return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
  }
}
