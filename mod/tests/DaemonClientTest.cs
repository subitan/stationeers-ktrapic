using System;
using System.Collections.Generic;
using System.IO;
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

        Assert.Null(response.error);
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

        Assert.Equal(1, error.error.line);
        Assert.Contains("nope", error.error.description);
        Assert.Null(compact.error);
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
}
