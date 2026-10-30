using System;
using System.Collections.Concurrent;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;

namespace KTrapIC
{
    /// <summary>
    /// A running compile daemon in its Language Server Protocol mode (<c>ktrapic-daemon --lsp</c>):
    /// a java process running the daemon jar, spoken to in JSON-RPC over its standard input and
    /// output, with <c>Content-Length</c> framing and UTF-8 bodies written and read as raw bytes. It
    /// sends <c>initialize</c> and <c>initialized</c> itself, as soon as the process starts.
    ///
    /// <para><b>Order.</b> Every message goes out in the order it was handed over, whichever
    /// thread hands it over: <see cref="Notify"/> and <see cref="Request"/> only queue it, and one
    /// writer thread writes the queue. The daemon handles messages in order too, so a request
    /// sees every change sent before it, such as a <c>ktrapic/compile</c> after a
    /// <c>didChange</c>. Nothing here waits for the pipe, so the game's main thread never
    /// blocks on a busy daemon.</para>
    ///
    /// <para><b>Threads.</b> Responses and notifications are read on a reader thread:
    /// <see cref="Diagnostics"/> is raised there, and a request's task completes on the thread
    /// pool. Once the process exits, or reading or writing fails, or the connection is disposed,
    /// it is stopped for good: requests waiting fail with <see cref="StoppedException"/>, and
    /// messages handed over afterwards are dropped. A stopped daemon is replaced by a new
    /// connection, on which documents must be opened again.</para>
    ///
    /// <para>This class uses nothing from the game, so it can be tested on its own.</para>
    /// </summary>
    public sealed class LspConnection : IDisposable
    {
        /// <summary>
        /// The JVM options the daemon runs with, beside the game:
        /// <list type="bullet">
        /// <item><c>-Xmx256m</c>: the heap is most of the daemon's memory. Uncapped, the JVM lets
        /// it grow to a quarter of the RAM; measured on a 32 GB machine, 40 compiles of a 66-line
        /// program and one of a 5,000-line one peaked at 1.16 GB of working set with the default
        /// collector, 850 MB with the serial one, and 560 MB with this cap, with at most 110 MB of
        /// the heap live after a full collection. Warm compiles took 169 ms with these options,
        /// against 146 ms with the serial collector uncapped and 135 ms with the defaults (one
        /// run of each).</item>
        /// <item><c>-XX:+UseSerialGC</c>: one collector thread and no G1 bookkeeping. Pauses
        /// don't matter for a compiler that answers in a few hundred milliseconds.</item>
        /// <item><c>-XX:+ExitOnOutOfMemoryError</c>: a daemon out of memory exits instead of
        /// limping on, and the plugin starts it again with the next edit.</item>
        /// <item><c>-XX:+DisplayVMOutputToStderr</c>: the JVM's own messages, such as the reason
        /// for that exit, go to standard error, and so to the log, instead of standard output,
        /// which carries the protocol.</item>
        /// <item><c>-Dstderr.encoding=UTF-8</c>: its log, read as UTF-8 (the protocol itself is
        /// written as bytes).</item>
        /// </list>
        /// </summary>
        public static readonly string[] JvmOptions =
        {
            "-Xmx256m",
            "-XX:+UseSerialGC",
            "-XX:+ExitOnOutOfMemoryError",
            "-XX:+DisplayVMOutputToStderr",
            "-Dstderr.encoding=UTF-8",
        };

        /// <summary>Thrown, as a request's failure, once the connection has stopped.</summary>
        public sealed class StoppedException : Exception
        {
            public StoppedException(string message) : base(message) { }
        }

        /// <summary>A request's error response.</summary>
        public sealed class ResponseException : Exception
        {
            public readonly int Code;

            public ResponseException(int code, string message) : base(message) => Code = code;
        }

        /// <summary>Why a compile couldn't be answered, once the daemon has stopped.</summary>
        public const string StoppedMessage = "The KTrapIC compiler stopped; it starts again with the next edit.";

        private const int MethodNotFound = -32601;

        private readonly Process _process;
        private readonly Stream _input;
        private readonly Stream _output;
        private readonly Action<string> _log;
        private readonly BlockingCollection<byte[]> _outbox = new BlockingCollection<byte[]>();
        private readonly ConcurrentDictionary<int, TaskCompletionSource<JToken>> _pending =
            new ConcurrentDictionary<int, TaskCompletionSource<JToken>>();
        private readonly TaskCompletionSource<JToken> _initialized =
            new TaskCompletionSource<JToken>(TaskCreationOptions.RunContinuationsAsynchronously);
        private readonly TaskCompletionSource<string> _stopped =
            new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);
        private readonly TaskCompletionSource<bool> _released =
            new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        private int _nextId;
        private int _disposed;

        /// <summary>
        /// What still uses the process: the writer and reader threads, and the process itself
        /// until it has exited (<see cref="Release"/>).
        /// </summary>
        private int _users = 3;

        /// <summary>The exit code, kept from when it exited, as the process is freed afterwards.</summary>
        private int _exitCode;

        private volatile bool _exited;

        /// <summary>
        /// The <c>textDocument/publishDiagnostics</c> notifications' params, for every document, as
        /// they arrive, on the reader thread.
        /// </summary>
        public event Action<JObject> Diagnostics;

        /// <summary>How to start the daemon jar <paramref name="jar"/> in its LSP mode with <paramref name="java"/>.</summary>
        public static ProcessStartInfo StartInfo(string java, string jar) =>
            new ProcessStartInfo
            {
                FileName = java,
                Arguments = $"{string.Join(" ", JvmOptions)} -jar \"{jar}\" --lsp",
                UseShellExecute = false,
                // Otherwise a console window opens beside the game.
                CreateNoWindow = true,
                RedirectStandardInput = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                StandardErrorEncoding = Encoding.UTF8,
            };

        /// <summary>
        /// Starts <paramref name="jar"/> with <paramref name="java"/> and initializes it. What the
        /// daemon writes to its standard error goes to <paramref name="log"/>, a line at a time.
        /// </summary>
        public LspConnection(string java, string jar, Action<string> log)
            : this(StartInfo(java, jar), log)
        {
        }

        /// <summary>Starts the process <paramref name="startInfo"/> describes, which must redirect all three streams.</summary>
        internal LspConnection(ProcessStartInfo startInfo, Action<string> log)
        {
            _log = log;
            _process = new Process { StartInfo = startInfo, EnableRaisingEvents = true };
            _process.ErrorDataReceived += (_, e) =>
            {
                if (e.Data != null)
                    _log(e.Data);
            };
            _process.Exited += (_, __) =>
            {
                var code = ExitCode;
                if (code != null)
                {
                    _exitCode = code.Value;
                    _exited = true;
                }
                if (Volatile.Read(ref _disposed) == 0)
                    _log($"The KTrapIC compiler exited with code {code}");
                Stop(StoppedMessage);
                Release();
            };
            _process.Start();
            _process.BeginErrorReadLine();
            _input = _process.StandardInput.BaseStream;
            _output = _process.StandardOutput.BaseStream;
            new Thread(WriteLoop) { IsBackground = true, Name = "KTrapIC LSP writer" }.Start();
            new Thread(ReadLoop) { IsBackground = true, Name = "KTrapIC LSP reader" }.Start();
            Initialize();
        }

        /// <summary>
        /// Called once by each user of the process (<see cref="_users"/>) when it is done with it;
        /// the last call frees the process's handle and pipes, once its standard error has all
        /// been logged.
        /// </summary>
        private void Release()
        {
            if (Interlocked.Decrement(ref _users) != 0)
                return;
            Task.Run(() =>
            {
                try
                {
                    _process.WaitForExit();
                    _process.Dispose();
                    _released.TrySetResult(true);
                }
                catch (Exception e)
                {
                    _log($"Releasing the KTrapIC compiler's process failed: {e.Message}");
                    _released.TrySetResult(false);
                }
            });
        }

        /// <summary>
        /// Completes once the exited process has been freed (<see cref="Release"/>): true, or false
        /// if freeing it failed.
        /// </summary>
        internal Task<bool> Released => _released.Task;

        /// <summary>The daemon's process, for tests to check that it has been freed.</summary>
        internal Process ProcessForTest => _process;

        /// <summary>
        /// Completes with the <c>initialize</c> result once the daemon has answered it and
        /// <c>initialized</c> has been queued; fails with <see cref="StoppedException"/> if the
        /// connection stops first.
        /// </summary>
        public Task<JToken> Initialized => _initialized.Task;

        public bool IsInitialized => _initialized.Task.Status == TaskStatus.RanToCompletion && IsRunning;

        public bool IsRunning => !_stopped.Task.IsCompleted;

        /// <summary>Completes, with why, once the connection has stopped.</summary>
        public Task<string> Stopped => _stopped.Task;

        /// <summary>The daemon's exit code, once it has exited; null before.</summary>
        public int? ExitCode
        {
            get
            {
                try
                {
                    if (_exited)
                        return _exitCode;
                    return _process.HasExited ? _process.ExitCode : (int?)null;
                }
                catch (InvalidOperationException)
                {
                    return null;
                }
            }
        }

        /// <summary>Queues a notification.</summary>
        public void Notify(string method, JToken parameters) =>
            Enqueue(new JObject { ["jsonrpc"] = "2.0", ["method"] = method, ["params"] = parameters });

        /// <summary>
        /// Queues a request. Its task completes with the result, or fails with
        /// <see cref="ResponseException"/> on an error response, or with
        /// <see cref="StoppedException"/> if the connection stops before the response arrives.
        /// </summary>
        public Task<JToken> Request(string method, JToken parameters)
        {
            int id = Interlocked.Increment(ref _nextId);
            var response = new TaskCompletionSource<JToken>(TaskCreationOptions.RunContinuationsAsynchronously);
            _pending[id] = response;
            Enqueue(new JObject { ["jsonrpc"] = "2.0", ["id"] = id, ["method"] = method, ["params"] = parameters });
            // Stopped before or while it was queued: the reader won't fail it any more.
            if (!IsRunning)
                FailPending();
            return response.Task;
        }

        /// <summary>
        /// Compiles the open document <paramref name="uri"/> as it is after every change queued
        /// so far (the <c>ktrapic/compile</c> request), once the daemon is initialized. A
        /// daemon that stops, or answers with an error, gives a failed response rather than
        /// throwing.
        /// </summary>
        public async Task<CompileResponse> Compile(string uri)
        {
            try
            {
                await Initialized.ConfigureAwait(false);
                var result = await Request("ktrapic/compile", new JObject
                {
                    ["textDocument"] = new JObject { ["uri"] = uri },
                }).ConfigureAwait(false);
                return result.ToObject<CompileResponse>();
            }
            catch (StoppedException)
            {
                return CompileResponse.Failed(StoppedMessage);
            }
            catch (ResponseException e)
            {
                return CompileResponse.Failed($"The KTrapIC compiler couldn't compile: {e.Message}");
            }
        }

        /// <summary>
        /// Stops the daemon. This doesn't wait for requests being answered, which then fail, so it
        /// also stops a daemon that has hung.
        /// </summary>
        public void Dispose()
        {
            if (Interlocked.Exchange(ref _disposed, 1) == 1)
                return;
            Stop(StoppedMessage);
            try
            {
                if (!_process.HasExited)
                    _process.Kill();
            }
            catch (InvalidOperationException)
            {
                // It exited on its own in the meantime.
            }
            catch (System.ComponentModel.Win32Exception)
            {
                // It is exiting.
            }
        }

        private void Initialize()
        {
            var initialize = Request("initialize", new JObject
            {
                ["processId"] = Process.GetCurrentProcess().Id,
                ["rootUri"] = null,
                // Only what the plugin uses; the daemon offers what it implements whatever these say.
                ["capabilities"] = new JObject
                {
                    ["textDocument"] = new JObject
                    {
                        ["publishDiagnostics"] = new JObject { ["versionSupport"] = true },
                    },
                },
            });
            initialize.ContinueWith(
                task =>
                {
                    if (task.Status == TaskStatus.RanToCompletion)
                    {
                        // Queued before anyone waiting for Initialized can queue a message.
                        Notify("initialized", new JObject());
                        _initialized.TrySetResult(task.Result);
                    }
                    else
                    {
                        var e = task.Exception?.GetBaseException();
                        _log($"Initializing the KTrapIC compiler failed: {e?.Message}");
                        _initialized.TrySetException(e as StoppedException ?? new StoppedException(e?.Message));
                        Dispose();
                    }
                },
                TaskContinuationOptions.ExecuteSynchronously);
        }

        private void Enqueue(JObject message)
        {
            byte[] body = Encoding.UTF8.GetBytes(message.ToString(Formatting.None));
            byte[] header = Encoding.ASCII.GetBytes($"Content-Length: {body.Length}\r\n\r\n");
            var frame = new byte[header.Length + body.Length];
            Buffer.BlockCopy(header, 0, frame, 0, header.Length);
            Buffer.BlockCopy(body, 0, frame, header.Length, body.Length);
            try
            {
                _outbox.Add(frame);
            }
            catch (InvalidOperationException)
            {
                // Stopped: nothing more goes out.
            }
        }

        private void Stop(string why)
        {
            if (!_stopped.TrySetResult(why))
                return;
            _outbox.CompleteAdding();
            _initialized.TrySetException(new StoppedException(why));
            // Observed here, so a connection that never initialized doesn't leave an unobserved exception.
            _ = _initialized.Task.Exception;
            FailPending();
        }

        private void FailPending()
        {
            string why = _stopped.Task.IsCompleted ? _stopped.Task.Result : StoppedMessage;
            foreach (int id in _pending.Keys)
            {
                if (_pending.TryRemove(id, out var response))
                    response.TrySetException(new StoppedException(why));
            }
        }

        private void WriteLoop()
        {
            try
            {
                foreach (var frame in _outbox.GetConsumingEnumerable())
                {
                    _input.Write(frame, 0, frame.Length);
                    _input.Flush();
                }
            }
            catch (Exception e) when (e is IOException || e is ObjectDisposedException || e is InvalidOperationException)
            {
                Stop($"Writing to the KTrapIC compiler failed: {e.Message}");
            }
            finally
            {
                Release();
            }
        }

        private void ReadLoop()
        {
            string why = StoppedMessage;
            bool broken = false;
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
            catch (InvalidDataException e)
            {
                why = "The KTrapIC compiler doesn't speak the Language Server Protocol"
                    + $" (if DaemonJarPath is set, its jar may be too old): {e.Message}";
                _log(why);
                broken = true;
            }
            catch (Exception e) when (e is IOException || e is ObjectDisposedException)
            {
                // Its output closed: it exited, or was stopped.
            }
            catch (Exception e)
            {
                // An exception escaping this thread would end the game, so it ends the connection.
                why = $"Reading from the KTrapIC compiler failed: {e.Message}";
                _log(why);
                broken = true;
            }
            finally
            {
                Stop(why);
                // A daemon that can't be understood is of no use, though still running.
                if (broken)
                    Dispose();
                Release();
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
                {
                    if (length < 0)
                        throw new InvalidDataException("A message without a Content-Length header");
                    return length;
                }
                const string name = "Content-Length:";
                if (header.StartsWith(name, StringComparison.OrdinalIgnoreCase))
                    length = int.Parse(header.Substring(name.Length).Trim());
                else if (header.IndexOf(':') < 0)
                    throw new InvalidDataException($"not a header: {(header.Length > 60 ? header.Substring(0, 60) + "..." : header)}");
            }
        }

        private void Dispatch(JObject message)
        {
            var id = message["id"];
            string method = (string)message["method"];
            if (method == null)
            {
                if (id?.Type == JTokenType.Integer && _pending.TryRemove((int)id, out var response))
                {
                    if (message["error"] is JObject error)
                        response.TrySetException(new ResponseException((int?)error["code"] ?? 0, (string)error["message"]));
                    else
                        response.TrySetResult(message["result"] ?? JValue.CreateNull());
                }
            }
            else if (id != null)
            {
                // The daemon sends no requests, but a request must be answered.
                Enqueue(new JObject
                {
                    ["jsonrpc"] = "2.0",
                    ["id"] = id,
                    ["error"] = new JObject { ["code"] = MethodNotFound, ["message"] = $"Unknown method {method}" },
                });
            }
            else if (method == "textDocument/publishDiagnostics" && message["params"] is JObject parameters)
            {
                try
                {
                    Diagnostics?.Invoke(parameters);
                }
                catch (Exception e)
                {
                    _log($"Handling diagnostics failed: {e}");
                }
            }
        }
    }
}
