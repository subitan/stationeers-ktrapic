using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Threading;
using Newtonsoft.Json;

namespace KTrapIC
{
    /// <summary>
    /// The compile daemon's answer to a compile request (see daemon/ in this repository): the IC10
    /// code and its size on success, or the first error. Lines and columns in errors are 1-based;
    /// <see cref="source_mapping"/> maps 0-based source lines to 0-based lines of
    /// <see cref="code"/>.
    /// </summary>
    public class CompileResponse
    {
        public class Problem
        {
            public string description;
            public int line;
            public int column;
        }

        public string code;
        public int num_lines;
        public int num_bytes;
        public int num_registers;
        public List<Problem> warnings;
        public Dictionary<int, List<int>> source_mapping;
        public Problem error;

        /// <summary>
        /// True for a response the compiler didn't give (it couldn't start, or stopped), which says
        /// nothing about the program.
        /// </summary>
        [JsonIgnore]
        public bool Unavailable;

        public static CompileResponse Failed(string description) =>
            new CompileResponse { error = new Problem { description = description }, Unavailable = true };
    }

    /// <summary>
    /// A running compile daemon: a java process running the daemon jar, which takes one request per
    /// line of base64-encoded JSON on its standard input and answers each with one such line on its
    /// standard output. Requests are answered one at a time, from any thread. This class uses
    /// nothing from the game, so it can be tested on its own.
    /// </summary>
    public sealed class DaemonClient : IDisposable
    {
        private readonly Process _process;
        private readonly object _lock = new object();
        private int _disposed;

        /// <summary>
        /// Starts <paramref name="jar"/> with <paramref name="java"/>. What the daemon writes to its
        /// standard error goes to <paramref name="log"/>.
        /// </summary>
        public DaemonClient(string java, string jar, Action<string> log)
        {
            _process = new Process
            {
                StartInfo = new ProcessStartInfo
                {
                    FileName = java,
                    Arguments = $"-jar \"{jar}\"",
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
                    log(e.Data);
            };
            _process.Start();
            _process.BeginErrorReadLine();
            _process.StandardInput.NewLine = "\n";
        }

        public bool IsRunning => Volatile.Read(ref _disposed) == 0 && !_process.HasExited;

        /// <summary>
        /// Compiles <paramref name="source"/>, a whole program, waiting for any request already
        /// being answered. A daemon that has stopped gives a failed response rather than throwing.
        /// </summary>
        public CompileResponse Compile(string source, bool compact)
        {
            var request = new Dictionary<string, object>
            {
                ["action"] = "compile",
                ["code"] = new Dictionary<string, string> { [""] = source },
                ["options"] = new Dictionary<string, object> { ["compact"] = compact },
            };
            string answer;
            lock (_lock)
            {
                if (!IsRunning)
                    return CompileResponse.Failed(Stopped);
                try
                {
                    _process.StandardInput.WriteLine(Encode(JsonConvert.SerializeObject(request)));
                    _process.StandardInput.Flush();
                    answer = _process.StandardOutput.ReadLine();
                }
                // Stopped while being written to or read from, by Dispose or on its own.
                catch (Exception e) when (e is IOException || e is ObjectDisposedException || e is InvalidOperationException)
                {
                    return CompileResponse.Failed(Stopped);
                }
            }
            if (answer == null)
                return CompileResponse.Failed(Stopped);
            return JsonConvert.DeserializeObject<CompileResponse>(Decode(answer));
        }

        /// <summary>
        /// Stops the daemon. This doesn't wait for a request being answered, which then fails, so it
        /// also stops a daemon that has hung.
        /// </summary>
        public void Dispose()
        {
            if (Interlocked.Exchange(ref _disposed, 1) == 1)
                return;
            try
            {
                if (!_process.HasExited)
                    _process.Kill();
            }
            catch (InvalidOperationException)
            {
                // It exited on its own in the meantime.
            }
            _process.Dispose();
        }

        private const string Stopped = "The KTrapIC compiler stopped; it starts again with the next edit.";

        private static string Encode(string text) => Convert.ToBase64String(Encoding.UTF8.GetBytes(text));

        private static string Decode(string line) => Encoding.UTF8.GetString(Convert.FromBase64String(line));
    }
}
