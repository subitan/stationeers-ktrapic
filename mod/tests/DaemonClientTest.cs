using System;
using System.Collections.Generic;
using System.IO;
using System.Text;
using System.Threading.Tasks;
using KTrapIC;
using Xunit;

public class DaemonClientTest : IDisposable
{
    private readonly List<string> _log = new List<string>();
    private readonly DaemonClient _client;

    public DaemonClientTest()
    {
        string jar = Environment.GetEnvironmentVariable("KTRAPIC_DAEMON_JAR")
            ?? throw new InvalidOperationException("Set KTRAPIC_DAEMON_JAR to a built ktrapic-daemon_deploy.jar");
        // java would resolve a relative path against the test's working directory, its build output.
        if (!Path.IsPathRooted(jar) || !File.Exists(jar))
            throw new InvalidOperationException($"KTRAPIC_DAEMON_JAR must be the absolute path of a jar that exists: {jar}");
        string javaHome = Environment.GetEnvironmentVariable("JAVA_HOME");
        string java = javaHome == null
            ? "java"
            : Path.Combine(javaHome, "bin", OperatingSystem.IsWindows() ? "java.exe" : "java");
        _client = new DaemonClient(java, jar, line => { lock (_log) _log.Add(line); });
    }

    public void Dispose() => _client.Dispose();

    [Fact]
    public void CompilesAProgramWithItsSizeAndSourceMap()
    {
        var response = _client.Compile("var i = 0\nwhile (i < 3) i++\ndb.Setting = i.toDouble()\n", false);

        AssertCompiled(response);
        Assert.Contains("s db Setting r0", response.code);
        Assert.Equal(response.code.Split('\n').Length, response.num_lines);
        Assert.Equal(1, response.num_registers);
        // Source line 2, the write, compiled to the code's last line.
        Assert.Equal(new List<int> { response.num_lines - 1 }, response.source_mapping[2]);
    }

    [Fact]
    public void PassesCompactAndKeepsAnsweringAfterAnError()
    {
        var error = _client.Compile("db.Setting = nope\n", false);
        var compact = _client.Compile("var i = 0\nwhile (i < 3) i++\n", true);

        Assert.True(error.error != null, DaemonLog());
        Assert.Equal(1, error.error.line);
        Assert.Contains("nope", error.error.description);
        AssertCompiled(compact);
        Assert.DoesNotContain(":", compact.code);
    }

    [Fact]
    public void AStoppedDaemonGivesAFailedResponse()
    {
        _client.Dispose();

        var response = _client.Compile("db.Setting = 1.0\n", false);

        Assert.NotNull(response.error);
        Assert.Null(response.code);
    }

    [Fact]
    public async Task DisposingDuringACompileFailsItPromptly()
    {
        // Long enough to take many seconds to compile.
        var program = new StringBuilder("var x = 0\n");
        for (int i = 0; i < 40000; i++)
            program.Append("x = x + 1\n");
        program.Append("db.Setting = x.toDouble()\n");
        var compile = Task.Run(() => _client.Compile(program.ToString(), false));
        await Task.Delay(500);
        Assert.False(compile.IsCompleted, "The compile finished before Dispose; it should take longer.");

        _client.Dispose();

        var done = await Task.WhenAny(compile, Task.Delay(TimeSpan.FromSeconds(10)));
        Assert.True(done == compile, "Compile didn't return after Dispose.");
        var response = await compile;
        Assert.NotNull(response.error);
        Assert.Null(response.code);
    }

    private void AssertCompiled(CompileResponse response) =>
        Assert.True(response.error == null, $"{response.error?.description}\n{DaemonLog()}");

    private string DaemonLog()
    {
        lock (_log)
            return "Compiler's standard error:\n" + string.Join("\n", _log);
    }
}
