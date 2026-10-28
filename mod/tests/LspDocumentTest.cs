using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Threading.Tasks;
using KTrapIC;
using Newtonsoft.Json.Linq;
using Xunit;

/// <summary>
/// The plugin's client of the daemon (<see cref="LspConnection"/> and <see cref="LspDocument"/>),
/// as the Kotlin editors use it, against a real daemon: compiles for the preview and the export,
/// diagnostics for one editor's document only, and documents opened again on a daemon started
/// again.
/// </summary>
public class LspDocumentTest : IDisposable
{
    private readonly List<TestConnection> _daemons = new List<TestConnection>();

    public void Dispose()
    {
        foreach (var daemon in _daemons)
            daemon.Dispose();
    }

    private TestConnection Start(bool initialize = true)
    {
        var daemon = new TestConnection(initialize);
        _daemons.Add(daemon);
        return daemon;
    }

    /// <summary>A document open on <paramref name="daemon"/> with <paramref name="text"/>, as version 1.</summary>
    private static LspDocument Open(TestConnection daemon, string text, out BlockingCollection<JObject> diagnostics)
    {
        var document = new LspDocument(LspDocument.NewUri("chip"));
        var received = new BlockingCollection<JObject>();
        document.Diagnostics += received.Add;
        document.Bind(daemon.Connection);
        Assert.True(document.Sync(1, () => text, new JArray()));
        Assert.True(document.IsOpen);
        diagnostics = received;
        return document;
    }

    private static JArray Replace(int line, int start, int end, string text) =>
        new JArray(new JObject
        {
            ["range"] = new JObject
            {
                ["start"] = new JObject { ["line"] = line, ["character"] = start },
                ["end"] = new JObject { ["line"] = line, ["character"] = end },
            },
            ["text"] = text,
        });

    [Fact]
    public void StartsTheDaemonInLspModeWithTheJvmOptions()
    {
        var startInfo = LspConnection.StartInfo("java", "C:/Program Files/a b/ktrapic-daemon.jar");

        Assert.Equal("java", startInfo.FileName);
        Assert.Equal(
            "-Xmx256m -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError -XX:+DisplayVMOutputToStderr "
                + "-Dstderr.encoding=UTF-8 "
                + "-jar \"C:/Program Files/a b/ktrapic-daemon.jar\" --lsp",
            startInfo.Arguments);
        Assert.True(startInfo.CreateNoWindow);
        Assert.False(startInfo.UseShellExecute);
        Assert.True(startInfo.RedirectStandardInput && startInfo.RedirectStandardOutput && startInfo.RedirectStandardError);
    }

    [Fact]
    public void GivesEachDocumentItsOwnUri()
    {
        string first = LspDocument.NewUri("motherboard_id_7");
        string second = LspDocument.NewUri("motherboard_id_7");

        Assert.NotEqual(first, second);
        Assert.Matches("^ktrapic:///[0-9]+/motherboard_id_7\\.kt$", first);
        Assert.Matches("^ktrapic:///[0-9]+/my%20lib%2Fx\\.kt$", LspDocument.NewUri("my lib/x"));
    }

    [Fact]
    public async Task CompilesAnOpenDocumentWithItsSizeAndSourceMap()
    {
        var daemon = Start();
        var document = Open(daemon, "var i = 0\nwhile (i < 3) i++\ndb.Setting = i.toDouble()\n", out _);

        var response = await TestDaemon.Within(document.Compile(false));

        daemon.AssertCompiled(response);
        Assert.Contains("s db Setting r0", response.code);
        Assert.Equal(response.code.Split('\n').Length, response.num_lines);
        Assert.Equal(1, response.num_registers);
        // Source line 2, the write, compiled to the code's last line.
        Assert.Equal(new List<int> { response.num_lines - 1 }, response.source_mapping[2]);
        Assert.False(response.Unavailable);
    }

    [Fact]
    public async Task ACompileSeesEveryEditSentBeforeIt()
    {
        var daemon = Start();
        string text = "db.Setting = 0.0\n";
        var document = Open(daemon, text, out _);
        int start = text.IndexOf('0');
        var compiles = new List<(int, Task<CompileResponse>)>();

        // Each edit, then at once a compile, from this thread, as the editor sends them: each
        // compile must be of the text after its edit.
        for (int i = 1; i <= 20; i++)
        {
            string number = $"{i}.0";
            int end = text.Length - 1;
            Assert.True(document.Sync(i + 1, () => throw new InvalidOperationException("It's open"), Replace(0, start, end, number)));
            text = text.Substring(0, start) + number + "\n";
            compiles.Add((i, document.Compile(false)));
        }

        foreach (var (i, compile) in compiles)
        {
            var response = await TestDaemon.Within(compile);
            daemon.AssertCompiled(response);
            Assert.Equal($"s db Setting {i}", response.code);
        }
    }

    [Fact]
    public async Task PassesCompact()
    {
        var daemon = Start();
        var document = Open(daemon, "var i = 0\nwhile (i < 3) i++\n", out _);

        var compact = await TestDaemon.Within(document.Compile(true));
        var labelled = await TestDaemon.Within(document.Compile(false));

        daemon.AssertCompiled(compact);
        daemon.AssertCompiled(labelled);
        Assert.DoesNotContain(":", compact.code);
        Assert.Contains(":", labelled.code);
    }

    [Fact]
    public void DiagnosticsReachOnlyTheirOwnDocument()
    {
        var daemon = Start();
        var broken = Open(daemon, "db.Setting = nope\n", out var brokenDiagnostics);
        var clean = Open(daemon, "db.Setting = 1.0\n", out var cleanDiagnostics);

        var errors = daemon.Next(brokenDiagnostics);
        var none = daemon.Next(cleanDiagnostics);

        Assert.Equal(broken.Uri, (string)errors["uri"]);
        Assert.Contains("nope", (string)Assert.Single(errors["diagnostics"])["message"]);
        Assert.Equal(clean.Uri, (string)none["uri"]);
        Assert.Empty(none["diagnostics"]);

        // Closing a document publishes an empty list for it, which the IC10 Editor would apply
        // to every editor: it must not clear the other's errors.
        clean.Close();
        Assert.Null(clean.Connection);
        Assert.True(broken.Sync(2, null, Replace(0, 13, 17, "nada")));
        var stillBroken = daemon.Next(brokenDiagnostics);

        Assert.Equal(2, (int)stillBroken["version"]);
        Assert.Contains("nada", (string)Assert.Single(stillBroken["diagnostics"])["message"]);
        // Every document's diagnostics came on the connection, the closed one's empty list too.
        Assert.Contains(daemon.Diagnostics, d => (string)d["uri"] == clean.Uri && !d["diagnostics"].HasValues);
        Assert.Empty(cleanDiagnostics);
        Assert.Empty(brokenDiagnostics);
    }

    [Fact]
    public async Task OpensTheDocumentAgainOnADaemonStartedAgain()
    {
        var first = Start();
        string text = "db.Setting = nope\n";
        var document = Open(first, text, out var diagnostics);
        Assert.True(document.Sync(2, null, Replace(0, 13, 17, "1.0")));
        text = "db.Setting = 1.0\n";
        first.Connection.Dispose();

        var stopped = await TestDaemon.Within(document.Compile(false));
        Assert.True(stopped.Unavailable);
        Assert.Equal(LspConnection.StoppedMessage, stopped.error.description);
        Assert.False(document.Sync(3, () => text, new JArray()));

        var second = Start();
        document.Bind(second.Connection);
        Assert.False(document.IsOpen);
        // Opened again with the whole text, with no changes left to send.
        Assert.True(document.Sync(3, () => text, new JArray()));
        Assert.True(document.IsOpen);

        // Diagnostics from the new daemon, for the version it was opened at; any still on their
        // way from the first one were for older versions.
        JObject reopened;
        do
            reopened = second.Next(diagnostics);
        while ((int?)reopened["version"] != 3);
        Assert.Empty(reopened["diagnostics"]);
        var response = await TestDaemon.Within(document.Compile(false));
        second.AssertCompiled(response);
        Assert.Equal("s db Setting 1", response.code);
    }

    [Theory]
    [InlineData(0)]
    [InlineData(1)]
    [InlineData(2)]
    public async Task RemovingALineLeavesTheDaemonWithTheEditorsText(int index)
    {
        var lines = new List<string> { "db.Setting = 1.0", "db.Setting = 2.0", "db.Setting = 3.0" };
        var daemon = Start();
        var document = Open(daemon, string.Join("\n", lines), out _);

        var removal = LspDocument.LineRemoval(index, lines.Count, i => lines[i].Length);
        Assert.True(document.Sync(2, null, new JArray(removal)));
        lines.RemoveAt(index);
        // Text inserted on the line after the last lands on a line of its own if the daemon kept
        // a line break the editor no longer has, and at the end of the last line if not.
        Assert.True(document.Sync(3, null, Replace(lines.Count, 0, 0, " + 4.0")));
        var edited = await TestDaemon.Within(document.Compile(false));

        var exact = Open(daemon, string.Join("\n", lines) + " + 4.0", out _);
        var expected = await TestDaemon.Within(exact.Compile(false));
        daemon.AssertCompiled(expected);
        daemon.AssertCompiled(edited);
        Assert.Equal(expected.code, edited.code);
    }

    [Fact]
    public async Task RemovingTheOnlyLineEmptiesTheText()
    {
        var daemon = Start();
        var document = Open(daemon, "db.Setting = nope", out _);

        Assert.True(document.Sync(2, null, new JArray(LspDocument.LineRemoval(0, 1, _ => 17))));
        Assert.True(document.Sync(3, null, Replace(0, 0, 0, "db.Setting = 5.0")));
        var response = await TestDaemon.Within(document.Compile(false));

        daemon.AssertCompiled(response);
        Assert.Equal("s db Setting 5", response.code);
    }

    [Fact]
    public void CompileNowCompilesTheLatestEditsOnThisThread()
    {
        var daemon = Start();
        var document = Open(daemon, "db.Setting = 1.0\n", out _);
        Assert.True(document.Sync(2, null, Replace(0, 13, 16, "2.0")));

        var response = document.CompileNow(false, TestDaemon.Timeout);

        daemon.AssertCompiled(response);
        Assert.Equal("s db Setting 2", response.code);
    }

    [Fact]
    public void CompileNowIsNullUntilTheDaemonIsInitialized()
    {
        // A process that reads everything and never answers, as a daemon still starting.
        string silent = Path.Combine(Path.GetTempPath(), $"ktrapic-silent-{Guid.NewGuid():N}.java");
        File.WriteAllText(silent,
            "class Silent { public static void main(String[] args) throws Exception {"
            + " System.in.transferTo(java.io.OutputStream.nullOutputStream()); Thread.sleep(Long.MAX_VALUE); } }");
        var startInfo = LspConnection.StartInfo(TestDaemon.Java, TestDaemon.Jar);
        startInfo.Arguments = $"\"{silent}\"";
        var daemon = new TestConnection(startInfo, initialize: false);
        _daemons.Add(daemon);
        var document = new LspDocument(LspDocument.NewUri("chip"));
        document.Bind(daemon.Connection);

        try
        {
            Assert.False(document.Sync(1, () => "db.Setting = 1.0\n", new JArray()));
            Assert.False(document.IsOpen);
            var watch = Stopwatch.StartNew();
            Assert.Null(document.CompileNow(false, TestDaemon.Timeout));
            Assert.True(watch.Elapsed < TimeSpan.FromSeconds(1), $"CompileNow waited {watch.Elapsed}");
            Assert.True(daemon.Connection.IsRunning, daemon.Log());
            Assert.False(daemon.Connection.IsInitialized);
        }
        finally
        {
            daemon.Dispose();
            File.Delete(silent);
        }
    }

    [Fact]
    public async Task AnOlderJarWithoutLspModeStopsTheConnection()
    {
        // The daemon in its line mode, as a jar from before --lsp runs whatever its arguments.
        var startInfo = LspConnection.StartInfo(TestDaemon.Java, TestDaemon.Jar);
        startInfo.Arguments = startInfo.Arguments.Replace(" --lsp", "");
        var daemon = new TestConnection(startInfo, initialize: false);
        _daemons.Add(daemon);

        string why = await TestDaemon.Within(daemon.Connection.Stopped, "The connection");

        Assert.Contains("doesn't speak the Language Server Protocol", why);
        await Assert.ThrowsAsync<LspConnection.StoppedException>(() => TestDaemon.Within(daemon.Connection.Initialized));
        Assert.True(daemon.Connection.ExitCode != null || SpinUntilExited(daemon.Connection), "The daemon wasn't stopped.");
    }

    private static bool SpinUntilExited(LspConnection connection)
    {
        var deadline = DateTime.UtcNow + TestDaemon.Timeout;
        while (connection.ExitCode == null && DateTime.UtcNow < deadline)
            System.Threading.Thread.Sleep(20);
        return connection.ExitCode != null;
    }

    [Fact]
    public void CompileNowGivesUpAfterItsTimeout()
    {
        var daemon = Start();
        var document = Open(daemon, TestDaemon.SlowProgram(), out _);

        var watch = Stopwatch.StartNew();
        var response = document.CompileNow(false, TimeSpan.FromMilliseconds(500));

        Assert.True(watch.Elapsed < TimeSpan.FromSeconds(5), $"CompileNow waited {watch.Elapsed}");
        Assert.True(response.Unavailable);
        Assert.Contains("didn't answer", response.error.description);
    }

    [Fact]
    public async Task DisposingDuringACompileFailsItPromptly()
    {
        var daemon = Start();
        var document = Open(daemon, TestDaemon.SlowProgram(), out _);
        var compile = document.Compile(false);
        await Task.Delay(500);
        Assert.False(compile.IsCompleted, "The compile finished before Dispose; it should take longer.");

        daemon.Connection.Dispose();

        var done = await Task.WhenAny(compile, Task.Delay(TimeSpan.FromSeconds(10)));
        Assert.True(done == compile, "Compile didn't return after Dispose.");
        var response = await compile;
        Assert.Null(response.code);
        Assert.True(response.Unavailable);
        Assert.False(daemon.Connection.IsRunning);
        Assert.True(daemon.Connection.Stopped.IsCompleted);
        await TestDaemon.AssertFreed(daemon.Connection);
        Assert.NotNull(daemon.Connection.ExitCode);
    }

    [Fact]
    public async Task ADaemonThatExitsStopsTheConnection()
    {
        var daemon = Start();
        var connection = daemon.Connection;
        var document = Open(daemon, "db.Setting = 1.0\n", out _);

        await TestDaemon.Within(connection.Request("shutdown", null));
        connection.Notify("exit", null);

        await TestDaemon.Within(connection.Stopped, "The connection");
        // Its process is freed once it has exited, and its exit code kept.
        await TestDaemon.AssertFreed(connection);
        Assert.Equal(0, connection.ExitCode);
        Assert.False(connection.IsInitialized);
        var request = connection.Request("ktrapic/compile", new JObject());
        await Assert.ThrowsAsync<LspConnection.StoppedException>(() => TestDaemon.Within(request));
        var response = await TestDaemon.Within(document.Compile(false));
        Assert.True(response.Unavailable);
        Assert.Null(document.CompileNow(false, TestDaemon.Timeout));
    }

    [Fact]
    public async Task ADaemonOutOfMemoryLogsWhy()
    {
        var startInfo = LspConnection.StartInfo(TestDaemon.Java, TestDaemon.Jar);
        // A later -Xmx wins: a heap too small for the slow program.
        startInfo.Arguments = startInfo.Arguments.Replace(" -jar ", " -Xmx48m -jar ");
        var daemon = new TestConnection(startInfo, initialize: true);
        _daemons.Add(daemon);
        var document = Open(daemon, TestDaemon.SlowProgram(), out _);

        var response = await TestDaemon.Within(document.Compile(false));

        Assert.True(response.Unavailable, daemon.Log());
        await TestDaemon.Within(daemon.Connection.Stopped, "The connection");
        await TestDaemon.AssertFreed(daemon.Connection);
        Assert.Equal(3, daemon.Connection.ExitCode);
        Assert.Contains("Terminating due to java.lang.OutOfMemoryError", daemon.Log());
    }
}
