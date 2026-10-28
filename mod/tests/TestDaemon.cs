using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.IO;
using System.Text;
using System.Threading.Tasks;
using KTrapIC;
using Newtonsoft.Json.Linq;
using Xunit;

/// <summary>
/// The compile daemon the tests run: the jar given by KTRAPIC_DAEMON_JAR, run with JAVA_HOME's
/// java, or the java on the path.
/// </summary>
internal static class TestDaemon
{
    public static readonly TimeSpan Timeout = TimeSpan.FromSeconds(60);

    public static string Jar
    {
        get
        {
            string jar = Environment.GetEnvironmentVariable("KTRAPIC_DAEMON_JAR")
                ?? throw new InvalidOperationException("Set KTRAPIC_DAEMON_JAR to a built ktrapic-daemon_deploy.jar");
            // java would resolve a relative path against the test's working directory, its build output.
            if (!Path.IsPathRooted(jar) || !File.Exists(jar))
                throw new InvalidOperationException($"KTRAPIC_DAEMON_JAR must be the absolute path of a jar that exists: {jar}");
            return jar;
        }
    }

    public static string Java
    {
        get
        {
            string javaHome = Environment.GetEnvironmentVariable("JAVA_HOME");
            return javaHome == null
                ? "java"
                : Path.Combine(javaHome, "bin", OperatingSystem.IsWindows() ? "java.exe" : "java");
        }
    }

    /// <summary><paramref name="task"/>'s result, failing the test if it takes longer than <see cref="Timeout"/>.</summary>
    public static async Task<T> Within<T>(Task<T> task, string what = "The daemon")
    {
        var done = await Task.WhenAny(task, Task.Delay(Timeout));
        Assert.True(done == task, $"{what} didn't answer within {Timeout}");
        return await task;
    }

    /// <summary>
    /// Waits for <paramref name="connection"/>'s process to be freed, and checks that it was: a
    /// disposed process has no handle.
    /// </summary>
    public static async Task AssertFreed(LspConnection connection)
    {
        Assert.True(await Within(connection.Released, "Freeing the process"));
        Assert.Throws<InvalidOperationException>(() => connection.ProcessForTest.Handle);
    }

    /// <summary>A program long enough to take many seconds to compile.</summary>
    public static string SlowProgram()
    {
        var program = new StringBuilder("var x = 0\n");
        for (int i = 0; i < 40000; i++)
            program.Append("x = x + 1\n");
        program.Append("db.Setting = x.toDouble()\n");
        return program.ToString();
    }
}

/// <summary>
/// A daemon started as the plugin starts it (<see cref="LspConnection"/>), whose standard error
/// and diagnostics the test can look at.
/// </summary>
internal sealed class TestConnection : IDisposable
{
    private readonly List<string> _log = new List<string>();

    public readonly LspConnection Connection;

    /// <summary>Every publishDiagnostics notification's params, in order.</summary>
    public readonly BlockingCollection<JObject> Diagnostics = new BlockingCollection<JObject>();

    public TestConnection(bool initialize = true)
        : this(LspConnection.StartInfo(TestDaemon.Java, TestDaemon.Jar), initialize)
    {
    }

    public TestConnection(System.Diagnostics.ProcessStartInfo startInfo, bool initialize)
    {
        Connection = new LspConnection(startInfo, line => { lock (_log) _log.Add(line); });
        Connection.Diagnostics += Diagnostics.Add;
        if (initialize)
            Assert.True(Connection.Initialized.Wait(TestDaemon.Timeout), $"The daemon didn't initialize.\n{Log()}");
    }

    public void Dispose() => Connection.Dispose();

    public string Log()
    {
        lock (_log)
            return "Daemon's standard error:\n" + string.Join("\n", _log);
    }

    /// <summary>The next diagnostics <paramref name="queue"/> gets.</summary>
    public JObject Next(BlockingCollection<JObject> queue)
    {
        Assert.True(queue.TryTake(out var parameters, TestDaemon.Timeout), $"No diagnostics were published.\n{Log()}");
        return parameters;
    }

    public void AssertCompiled(CompileResponse response) =>
        Assert.True(response.error == null, $"{response.error?.description}\n{Log()}");
}
