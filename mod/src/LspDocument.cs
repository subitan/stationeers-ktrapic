using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using Newtonsoft.Json.Linq;

namespace KTrapIC
{
    /// <summary>
    /// One editor's program as a document on the daemon's LSP connection, under a URI no other
    /// editor has (<see cref="NewUri"/>).
    ///
    /// <para>It is bound to one <see cref="LspConnection"/> at a time. Binding it to another, the
    /// daemon started again after a restart or a crash, forgets that it was open, so the next
    /// <see cref="Sync"/> opens it there with its whole text.</para>
    ///
    /// <para>The daemon sends every document's diagnostics on the one connection, and the IC10
    /// Editor applies any it is given, so <see cref="Diagnostics"/> passes on only this
    /// document's.</para>
    ///
    /// <para><see cref="Bind"/>, <see cref="Sync"/> and <see cref="Close"/> are called from one
    /// thread, the game's main thread in game; compiles and completions from any.</para>
    ///
    /// <para>This class uses nothing from the game, so it can be tested on its own.</para>
    /// </summary>
    public sealed class LspDocument
    {
        private static int _count;

        private volatile LspConnection _connection;
        private volatile bool _isOpen;

        /// <summary>
        /// This document's <c>publishDiagnostics</c> params, on the connection's reader thread.
        /// Their <c>version</c> is the one <see cref="Sync"/> last sent.
        /// </summary>
        public event Action<JObject> Diagnostics;

        public LspDocument(string uri) => Uri = uri;

        /// <summary>
        /// A URI for a new document, unique in this game session, such as
        /// <c>ktrapic:///3/motherboard_id_1234.kt</c>: a number, then <paramref name="name"/>, the
        /// editor's file name, to tell documents apart in the log. Nothing is read from it.
        /// </summary>
        public static string NewUri(string name) =>
            $"ktrapic:///{Interlocked.Increment(ref _count)}/{System.Uri.EscapeDataString(string.IsNullOrEmpty(name) ? "Untitled" : name)}.kt";

        public string Uri { get; }

        /// <summary>
        /// The LSP <c>TextDocumentContentChangeEvent</c> that removes line <paramref name="index"/>
        /// of a text of <paramref name="lineCount"/> lines, whose lengths <paramref name="lengthOf"/>
        /// gives: the line and the line break after it, or, for the last line, which has none after
        /// it, the line break before it. Removing the only line leaves it empty.
        /// </summary>
        public static JObject LineRemoval(int index, int lineCount, Func<int, int> lengthOf)
        {
            bool last = index == lineCount - 1 && index > 0;
            var start = last ? Position(index - 1, lengthOf(index - 1)) : Position(index, 0);
            var end = last ? Position(index, lengthOf(index)) : Position(index + 1, 0);
            return new JObject { ["range"] = new JObject { ["start"] = start, ["end"] = end }, ["text"] = "" };
        }

        private static JObject Position(int line, int character) =>
            new JObject { ["line"] = line, ["character"] = character };

        /// <summary>The connection it is bound to; null before <see cref="Bind"/> and after <see cref="Close"/>.</summary>
        public LspConnection Connection => _connection;

        /// <summary>Whether it is open on <see cref="Connection"/>.</summary>
        public bool IsOpen => _isOpen;

        /// <summary>
        /// Binds it to <paramref name="connection"/>, if it isn't already, as not open there. The
        /// connection it was bound to is left as it is: a stopped one has forgotten it anyway.
        /// </summary>
        public void Bind(LspConnection connection)
        {
            if (_connection == connection)
                return;
            Unbind();
            _isOpen = false;
            connection.Diagnostics += OnDiagnostics;
            _connection = connection;
        }

        /// <summary>
        /// Brings the daemon's copy up to date, if the connection is initialized: opens the
        /// document with <paramref name="text"/> if it isn't open, or else sends
        /// <paramref name="changes"/> (LSP <c>TextDocumentContentChangeEvent</c>s, if any).
        /// <paramref name="version"/> is the version they bring the document to. Returns whether
        /// it was sent, so that the caller can drop the changes; if not, they're still to send.
        /// </summary>
        public bool Sync(int version, Func<string> text, JArray changes)
        {
            var connection = _connection;
            if (connection == null || !connection.IsInitialized)
                return false;
            if (!_isOpen)
            {
                connection.Notify("textDocument/didOpen", new JObject
                {
                    ["textDocument"] = new JObject
                    {
                        ["uri"] = Uri,
                        ["languageId"] = "kotlin",
                        ["version"] = version,
                        ["text"] = text(),
                    },
                });
                _isOpen = true;
            }
            else if (changes.Count > 0)
            {
                connection.Notify("textDocument/didChange", new JObject
                {
                    ["textDocument"] = new JObject { ["uri"] = Uri, ["version"] = version },
                    ["contentChanges"] = changes,
                });
            }
            return true;
        }

        /// <summary>
        /// Compiles the document as of the last <see cref="Sync"/>. A failed response if it isn't
        /// open, or the daemon stops.
        /// </summary>
        public Task<CompileResponse> Compile()
        {
            var connection = _connection;
            if (connection == null || !_isOpen)
                return Task.FromResult(CompileResponse.Failed(LspConnection.StoppedMessage));
            return connection.Compile(Uri);
        }

        /// <summary>
        /// Compiles the document as of the last <see cref="Sync"/> on this thread, waiting at most
        /// <paramref name="timeout"/>, as an export needs. Null if it isn't open on an initialized
        /// connection, so this never waits for the daemon to start.
        /// </summary>
        public CompileResponse CompileNow(TimeSpan timeout)
        {
            var connection = _connection;
            if (connection == null || !connection.IsInitialized || !_isOpen)
                return null;
            // Run where no synchronization context would post the continuations back to this
            // thread, which is blocked waiting for them.
            var compile = Task.Run(() => Compile());
            if (compile.Wait(timeout))
                return compile.Result;
            return CompileResponse.Failed(
                $"The KTrapIC compiler didn't answer within {timeout.TotalSeconds:0} seconds; export again in a moment.");
        }

        /// <summary>
        /// What could be written at <paramref name="line"/> and <paramref name="character"/>
        /// (0-based, in UTF-16 code units) of the document as of the last <see cref="Sync"/>: the
        /// daemon's <c>textDocument/completion</c> items. Null if there is no answer: the document
        /// isn't open, or the daemon stops or answers with an error.
        /// </summary>
        public async Task<IReadOnlyList<Completion>> Complete(int line, int character)
        {
            var result = await Ask("textDocument/completion", line, character).ConfigureAwait(false);
            if (result == null)
                return null;
            return (result as JObject)?["items"]?.ToObject<List<Completion>>() ?? new List<Completion>();
        }

        /// <summary>
        /// What the name at <paramref name="line"/> and <paramref name="character"/> of the
        /// document as of the last <see cref="Sync"/> is: the text of the daemon's
        /// <c>textDocument/hover</c>, "" where it has nothing to say, or null if there is no
        /// answer, as for <see cref="Complete"/>.
        /// </summary>
        public async Task<string> Hover(int line, int character)
        {
            var result = await Ask("textDocument/hover", line, character).ConfigureAwait(false);
            if (result == null)
                return null;
            var contents = (result as JObject)?["contents"];
            return (contents as JObject)?["value"]?.ToString() ?? (contents as JValue)?.ToString() ?? "";
        }

        /// <summary>
        /// The functions the call around <paramref name="line"/> and <paramref name="character"/>
        /// of the document as of the last <see cref="Sync"/> could be to: the daemon's
        /// <c>textDocument/signatureHelp</c>, with none outside a call, or null if there is no
        /// answer, as for <see cref="Complete"/>.
        /// </summary>
        public async Task<Signatures> SignatureHelp(int line, int character)
        {
            var result = await Ask("textDocument/signatureHelp", line, character).ConfigureAwait(false);
            if (result == null)
                return null;
            return Signatures.FromLsp(result as JObject);
        }

        /// <summary>
        /// The daemon's result for a request about a position in the document as of the last
        /// <see cref="Sync"/>, a JSON null where it has none, or null if there is no answer: the
        /// document isn't open, or the daemon stops or answers with an error.
        /// </summary>
        internal async Task<JToken> Ask(string method, int line, int character)
        {
            var connection = _connection;
            if (connection == null || !connection.IsInitialized || !_isOpen)
                return null;
            try
            {
                var result = await connection.Request(method, new JObject
                {
                    ["textDocument"] = new JObject { ["uri"] = Uri },
                    ["position"] = Position(line, character),
                }).ConfigureAwait(false);
                return result ?? JValue.CreateNull();
            }
            catch (LspConnection.StoppedException)
            {
                return null;
            }
            catch (LspConnection.ResponseException)
            {
                return null;
            }
        }

        /// <summary>Closes the document on the daemon, if it is open, and unbinds it.</summary>
        public void Close()
        {
            var connection = _connection;
            if (connection != null && _isOpen)
                connection.Notify("textDocument/didClose", new JObject { ["textDocument"] = new JObject { ["uri"] = Uri } });
            Unbind();
        }

        private void Unbind()
        {
            var connection = _connection;
            if (connection != null)
                connection.Diagnostics -= OnDiagnostics;
            _connection = null;
            _isOpen = false;
        }

        private void OnDiagnostics(JObject parameters)
        {
            if ((string)parameters["uri"] == Uri)
                Diagnostics?.Invoke(parameters);
        }
    }
}
