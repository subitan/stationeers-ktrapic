using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using KTrapIC;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;
using Xunit;

/// <summary>
/// Drives the daemon jar's LSP mode (<c>--lsp</c>) as the plugin will, through
/// <see cref="LspTestClient"/>. The IC10 Editor's own client can't run outside the game.
/// </summary>
public class DaemonLspTest : IDisposable
{
    private static readonly TimeSpan Timeout = TimeSpan.FromSeconds(60);
    private const string Uri = "file:///chip.kt";

    private readonly LspTestClient _client = new LspTestClient(TestDaemon.Java, TestDaemon.Jar);

    public void Dispose() => _client.Dispose();

    [Fact]
    public void PublishesDiagnosticsAsYouTypeAndCompilesOnRequest()
    {
        var initialized = _client.Request("initialize", new JObject { ["processId"] = null, ["rootUri"] = null });
        _client.Notify("initialized", new JObject());
        Assert.Equal(2, (int)initialized["capabilities"]["textDocumentSync"]["change"]);

        // The emoji before the error is two UTF-16 code units, which LSP's characters count.
        string broken = "/* \U0001F600 */ db.Foo = 1.0\n";
        _client.Notify("textDocument/didOpen", new JObject
        {
            ["textDocument"] = new JObject
            {
                ["uri"] = Uri, ["languageId"] = "python", ["version"] = 1, ["text"] = broken,
            },
        });
        var error = _client.NextDiagnostics();
        Assert.Equal(Uri, (string)error["uri"]);
        Assert.Equal(1, (int)error["version"]);
        var diagnostic = Assert.Single(error["diagnostics"]);
        Assert.Equal(1, (int)diagnostic["severity"]);
        Assert.Contains("Foo", (string)diagnostic["message"]);
        int foo = broken.IndexOf("Foo", StringComparison.Ordinal);
        Assert.Equal<JToken>(Range(0, foo, 0, foo + 3), diagnostic["range"], JToken.EqualityComparer);

        // Replace Foo with Setting, an incremental change positioned in UTF-16 code units.
        _client.Notify("textDocument/didChange", new JObject
        {
            ["textDocument"] = new JObject { ["uri"] = Uri, ["version"] = 2 },
            ["contentChanges"] = new JArray(new JObject { ["range"] = Range(0, foo, 0, foo + 3), ["text"] = "Setting" }),
        });
        var fixedDiagnostics = _client.NextDiagnostics();
        Assert.Equal(2, (int)fixedDiagnostics["version"]);
        Assert.Empty(fixedDiagnostics["diagnostics"]);

        var compiled = _client.Request("ktrapic/compile", new JObject
        {
            ["textDocument"] = new JObject { ["uri"] = Uri },
            ["options"] = new JObject { ["compact"] = false },
        });
        AssertSameAsLineProtocol(compiled, "/* \U0001F600 */ db.Setting = 1.0\n");
        Assert.Equal("s db Setting 1", (string)compiled["code"]);

        var tokens = _client.Request("textDocument/semanticTokens/full", new JObject
        {
            ["textDocument"] = new JObject { ["uri"] = Uri },
        });
        Assert.Empty(tokens["data"]);

        Assert.Equal(JTokenType.Null, _client.Request("shutdown", null).Type);
        _client.Notify("exit", null);
        Assert.Equal(0, _client.WaitForExit(Timeout));
    }

    private static void AssertSameAsLineProtocol(JToken lspResult, string source)
    {
        CompileResponse expected;
        using (var lineClient = new DaemonClient(TestDaemon.Java, TestDaemon.Jar, _ => { }))
            expected = lineClient.Compile(source, false);
        var actual = lspResult.ToObject<CompileResponse>();
        Assert.Null(expected.error);
        Assert.Null(actual.error);
        Assert.Equal(expected.code, actual.code);
        Assert.Equal(expected.num_lines, actual.num_lines);
        Assert.Equal(expected.num_bytes, actual.num_bytes);
        Assert.Equal(expected.num_registers, actual.num_registers);
        Assert.Equal(expected.warnings.Count, actual.warnings.Count);
        Assert.Equal(expected.source_mapping, actual.source_mapping);
        // The same fields, with nothing left over that CompileResponse doesn't read.
        Assert.Equal(
            new[] { "code", "num_bytes", "num_lines", "num_registers", "source_mapping", "warnings" },
            ((JObject)lspResult).Properties().Select(p => p.Name).OrderBy(n => n, StringComparer.Ordinal));
    }

    private static JObject Range(int startLine, int startCharacter, int endLine, int endCharacter) =>
        new JObject
        {
            ["start"] = new JObject { ["line"] = startLine, ["character"] = startCharacter },
            ["end"] = new JObject { ["line"] = endLine, ["character"] = endCharacter },
        };
}

/// <summary>
/// A minimal JSON-RPC client for the daemon's LSP mode, for tests only: <c>Content-Length</c>
/// framing over the process's raw standard streams, with UTF-8 bodies. Requests wait for their
/// response; <c>publishDiagnostics</c> notifications are queued for <see cref="NextDiagnostics"/>.
/// </summary>
internal sealed class LspTestClient : IDisposable
{
    private static readonly TimeSpan Timeout = TimeSpan.FromSeconds(60);

    private readonly Process _process;
    private readonly Stream _input;
    private readonly Stream _output;
    private readonly Thread _reader;
    private readonly ConcurrentDictionary<int, TaskCompletionSource<JObject>> _pending =
        new ConcurrentDictionary<int, TaskCompletionSource<JObject>>();
    private readonly BlockingCollection<JObject> _diagnostics = new BlockingCollection<JObject>();
    private readonly List<string> _log = new List<string>();
    private int _nextId;

    public LspTestClient(string java, string jar)
    {
        _process = new Process
        {
            StartInfo = new ProcessStartInfo
            {
                FileName = java,
                Arguments = $"-jar \"{jar}\" --lsp",
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardInput = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
            },
        };
        _process.ErrorDataReceived += (_, e) =>
        {
            if (e.Data != null)
                lock (_log) _log.Add(e.Data);
        };
        _process.Start();
        _process.BeginErrorReadLine();
        _input = _process.StandardInput.BaseStream;
        _output = _process.StandardOutput.BaseStream;
        _reader = new Thread(ReadLoop) { IsBackground = true };
        _reader.Start();
    }

    /// <summary>Sends a request and returns its result, failing on an error response.</summary>
    public JToken Request(string method, JToken parameters)
    {
        int id = Interlocked.Increment(ref _nextId);
        var response = new TaskCompletionSource<JObject>(TaskCreationOptions.RunContinuationsAsynchronously);
        _pending[id] = response;
        Send(new JObject { ["jsonrpc"] = "2.0", ["id"] = id, ["method"] = method, ["params"] = parameters });
        Assert.True(response.Task.Wait(Timeout), $"No response to {method}.\n{Log()}");
        var message = response.Task.Result;
        Assert.True(message["error"] == null, $"{method} failed: {message["error"]}\n{Log()}");
        return message["result"];
    }

    public void Notify(string method, JToken parameters) =>
        Send(new JObject { ["jsonrpc"] = "2.0", ["method"] = method, ["params"] = parameters });

    /// <summary>The next <c>publishDiagnostics</c> notification's params.</summary>
    public JObject NextDiagnostics()
    {
        Assert.True(_diagnostics.TryTake(out var parameters, Timeout), $"No diagnostics were published.\n{Log()}");
        return parameters;
    }

    /// <summary>The process's exit code, once it has exited.</summary>
    public int WaitForExit(TimeSpan timeout)
    {
        Assert.True(_process.WaitForExit((int)timeout.TotalMilliseconds), "The daemon didn't exit.");
        return _process.ExitCode;
    }

    public void Dispose()
    {
        try
        {
            if (!_process.HasExited)
                _process.Kill();
        }
        catch (InvalidOperationException)
        {
            // It exited in the meantime.
        }
        _process.Dispose();
    }

    private void Send(JObject message)
    {
        byte[] body = Encoding.UTF8.GetBytes(message.ToString(Formatting.None));
        byte[] header = Encoding.ASCII.GetBytes($"Content-Length: {body.Length}\r\n\r\n");
        lock (_input)
        {
            _input.Write(header, 0, header.Length);
            _input.Write(body, 0, body.Length);
            _input.Flush();
        }
    }

    private void ReadLoop()
    {
        try
        {
            while (true)
            {
                int length = ReadContentLength();
                if (length < 0)
                    break;
                var body = new byte[length];
                int read = 0;
                while (read < length)
                {
                    int n = _output.Read(body, read, length - read);
                    if (n <= 0)
                        return;
                    read += n;
                }
                Dispatch(JObject.Parse(Encoding.UTF8.GetString(body)));
            }
        }
        catch (Exception e) when (e is IOException || e is ObjectDisposedException)
        {
            // The daemon stopped.
        }
        finally
        {
            _diagnostics.CompleteAdding();
        }
    }

    /// <summary>Reads a header block; its Content-Length, or -1 at the end of the output.</summary>
    private int ReadContentLength()
    {
        int length = -1;
        var line = new StringBuilder();
        while (true)
        {
            int b = _output.ReadByte();
            if (b < 0)
                return -1;
            if (b != '\n')
            {
                line.Append((char)b);
                continue;
            }
            string header = line.ToString().TrimEnd('\r');
            line.Clear();
            if (header.Length == 0)
                return length;
            const string name = "Content-Length:";
            if (header.StartsWith(name, StringComparison.OrdinalIgnoreCase))
                length = int.Parse(header.Substring(name.Length).Trim());
        }
    }

    private void Dispatch(JObject message)
    {
        if (message["method"] == null)
        {
            if (_pending.TryRemove((int)message["id"], out var response))
                response.SetResult(message);
        }
        else if ((string)message["method"] == "textDocument/publishDiagnostics")
        {
            _diagnostics.Add((JObject)message["params"]);
        }
    }

    private string Log()
    {
        lock (_log)
            return "Daemon's standard error:\n" + string.Join("\n", _log);
    }
}
