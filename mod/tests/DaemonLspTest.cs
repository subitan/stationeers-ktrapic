using System;
using System.Linq;
using System.Threading.Tasks;
using KTrapIC;
using Newtonsoft.Json.Linq;
using Xunit;

/// <summary>
/// Drives the daemon jar's LSP mode (<c>--lsp</c>) message by message, through the plugin's
/// connection to it (<see cref="LspConnection"/>), checking the protocol itself: what the
/// plugin's document and compile calls rely on.
/// </summary>
public class DaemonLspTest : IDisposable
{
    private const string Uri = "file:///chip.kt";

    private readonly TestConnection _daemon = new TestConnection();

    public void Dispose() => _daemon.Dispose();

    [Fact]
    public async Task PublishesDiagnosticsAsYouTypeAndCompilesOnRequest()
    {
        var connection = _daemon.Connection;
        Assert.Equal(2, (int)(await connection.Initialized)["capabilities"]["textDocumentSync"]["change"]);

        // The emoji before the error is two UTF-16 code units, which LSP's characters count.
        string broken = "/* \U0001F600 */ db.Foo = 1.0\n";
        connection.Notify("textDocument/didOpen", new JObject
        {
            ["textDocument"] = new JObject
            {
                ["uri"] = Uri, ["languageId"] = "kotlin", ["version"] = 1, ["text"] = broken,
            },
        });
        var error = _daemon.Next(_daemon.Diagnostics);
        Assert.Equal(Uri, (string)error["uri"]);
        Assert.Equal(1, (int)error["version"]);
        var diagnostic = Assert.Single(error["diagnostics"]);
        Assert.Equal(1, (int)diagnostic["severity"]);
        Assert.Contains("Foo", (string)diagnostic["message"]);
        int foo = broken.IndexOf("Foo", StringComparison.Ordinal);
        Assert.Equal<JToken>(Range(0, foo, 0, foo + 3), diagnostic["range"], JToken.EqualityComparer);

        // Replace Foo with Setting, an incremental change positioned in UTF-16 code units.
        connection.Notify("textDocument/didChange", new JObject
        {
            ["textDocument"] = new JObject { ["uri"] = Uri, ["version"] = 2 },
            ["contentChanges"] = new JArray(new JObject { ["range"] = Range(0, foo, 0, foo + 3), ["text"] = "Setting" }),
        });
        var fixedDiagnostics = _daemon.Next(_daemon.Diagnostics);
        Assert.Equal(2, (int)fixedDiagnostics["version"]);
        Assert.Empty(fixedDiagnostics["diagnostics"]);

        var compiled = await Request("ktrapic/compile", new JObject
        {
            ["textDocument"] = new JObject { ["uri"] = Uri },
            ["options"] = new JObject { ["compact"] = false },
        });
        Assert.Equal("s db Setting 1", (string)compiled["code"]);
        // The fields of a line-protocol compile, which CompileResponse reads; the daemon's tests
        // check they're the same values.
        Assert.Equal(
            new[] { "code", "num_bytes", "num_lines", "num_registers", "source_mapping", "warnings" },
            ((JObject)compiled).Properties().Select(p => p.Name).OrderBy(n => n, StringComparer.Ordinal));

        var tokens = await Request("textDocument/semanticTokens/full", new JObject
        {
            ["textDocument"] = new JObject { ["uri"] = Uri },
        });
        Assert.Empty(tokens["data"]);

        Assert.Equal(JTokenType.Null, (await Request("shutdown", null)).Type);
        connection.Notify("exit", null);
        await TestDaemon.Within(connection.Stopped, "The daemon's exit");
        Assert.True(SpinWaitForExitCode(connection) == 0, $"Exit code {connection.ExitCode}\n{_daemon.Log()}");
    }

    [Fact]
    public async Task AnswersAnUnknownRequestWithAnError()
    {
        var request = _daemon.Connection.Request("textDocument/completion", new JObject());

        var error = await Assert.ThrowsAsync<LspConnection.ResponseException>(() => TestDaemon.Within(request));
        Assert.Equal(-32601, error.Code);
        Assert.True(_daemon.Connection.IsRunning);
    }

    private Task<JToken> Request(string method, JToken parameters) =>
        TestDaemon.Within(_daemon.Connection.Request(method, parameters), $"{method}'s response");

    /// <summary>The exit code, which the process reports a moment after its output closes.</summary>
    private static int? SpinWaitForExitCode(LspConnection connection)
    {
        var deadline = DateTime.UtcNow + TestDaemon.Timeout;
        while (connection.ExitCode == null && DateTime.UtcNow < deadline)
            System.Threading.Thread.Sleep(20);
        return connection.ExitCode;
    }

    private static JObject Range(int startLine, int startCharacter, int endLine, int endCharacter) =>
        new JObject
        {
            ["start"] = new JObject { ["line"] = startLine, ["character"] = startCharacter },
            ["end"] = new JObject { ["line"] = endLine, ["character"] = endCharacter },
        };
}
