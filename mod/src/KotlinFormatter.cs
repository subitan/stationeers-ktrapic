using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Cysharp.Threading.Tasks;
using ImGuiEditor.LSP;
using ImGuiNET;
using Newtonsoft.Json.Linq;
using StationeersIC10Editor;
using StationeersIC10Editor.IC10;

namespace KTrapIC
{
    /// <summary>
    /// The IC10 Editor's "Kotlin" language: KTrapIC programs, highlighted, compiled as they're
    /// edited, with the IC10 shown in a read-only editor beside them and the IC10 lines of the
    /// caret's line highlighted there, with the compiler's errors marked in the code, and with
    /// completion of the names that could go at the caret (<see cref="UpdateAutocomplete"/>). Exporting
    /// writes the IC10 to the chip with the Kotlin source appended as encoded comments, which
    /// <see cref="ResetCode"/> reads back, so reopening the chip shows the Kotlin.
    ///
    /// <para><b>The daemon.</b> Each editor's program is a document (<see cref="LspDocument"/>,
    /// under its own URI) on the one compile daemon the tabs share, spoken to over LSP
    /// (<see cref="Daemon"/>). Edits go to it as they're made (<see cref="SubmitChanges"/>); it
    /// publishes the errors of each version, which are marked in the code, and compiles for the
    /// preview and the export are <c>ktrapic/compile</c> requests on the same connection. When the
    /// daemon starts again, after <c>ktrapic restart</c> or a crash, every editor opens its
    /// document on the new one (<see cref="OnDaemonReady"/>).</para>
    ///
    /// <para><b>Threads.</b> Edits, the document's messages and the error marks are handled on
    /// the main thread; diagnostics arrive on the connection's reader thread and are applied on
    /// the main thread, if they're still of the current version. Compiles wait on the thread
    /// pool.</para>
    /// </summary>
    public class KotlinFormatter : LSPFormatter
    {
        /// <summary>The tag of the Kotlin source encoded in the comments after the IC10.</summary>
        private const string SourceTag = "KTRAPIC_SOURCE";

        /// <summary>Starts the comment exported in place of a program that couldn't be compiled.</summary>
        private const string ErrorPrefix = "# KTrapIC: ";

        private const string CommentPrefix = "//";

        private const int DebounceMs = 150;

        /// <summary>
        /// How long an export waits for a compile, on the main thread. A warm compile takes well
        /// under a second, the first after the daemon starts about ten.
        /// </summary>
        private static readonly TimeSpan ExportTimeout = TimeSpan.FromSeconds(30);

        // Indices into the IC10 Editor's ColorTheme, as its other languages use them.
        private const uint Keyword = 15;
        private const uint ControlFlow = 23;
        private const uint Constant = 19;
        private const uint Type = 1;

        private static readonly Dictionary<string, uint> KotlinKeywords = new Dictionary<string, uint>
        {
            ["val"] = Keyword,
            ["var"] = Keyword,
            ["fun"] = Keyword,
            ["import"] = Keyword,
            ["inline"] = Keyword,
            ["in"] = Keyword,
            ["is"] = Keyword,
            ["as"] = Keyword,
            ["until"] = Keyword,
            ["downTo"] = Keyword,
            ["step"] = Keyword,
            ["if"] = ControlFlow,
            ["else"] = ControlFlow,
            ["when"] = ControlFlow,
            ["for"] = ControlFlow,
            ["while"] = ControlFlow,
            ["do"] = ControlFlow,
            ["return"] = ControlFlow,
            ["break"] = ControlFlow,
            ["continue"] = ControlFlow,
            ["true"] = Constant,
            ["false"] = Constant,
            ["Int"] = Type,
            ["Double"] = Type,
            ["Boolean"] = Type,
            ["Unit"] = Type,
        };

        /// <summary>Splits lines into tokens and colors keywords and comments, for <see cref="ParseLine"/>.</summary>
        private static readonly StaticFormatter Tokenizer = new StaticFormatter(
            TokenSeparators: " \t()[]{}:.,;+-*/%=<>!&|^~?@",
            StringDelimiters: "\"'",
            CommentPrefix: CommentPrefix,
            Keywords: KotlinKeywords,
            KeepWhitespaces: false);

        private static readonly uint PreviewHighlight = ColorFromHTML("#000080");

        // The SDK's names, in the colors the editor's theme gives types, enums, variables,
        // properties, enum members and functions.
        private static readonly Style TypeStyle = new Style { Color = ColorFromHTML("#4EC9B0") };
        private static readonly Style EnumStyle = new Style { Color = ColorFromHTML("#B8D7A3") };
        private static readonly Style DeviceStyle = new Style { Color = ColorFromHTML("#9CDCFE") };
        private static readonly Style LogicTypeStyle = new Style { Color = ColorFromHTML("#9CDCFE") };
        private static readonly Style EnumValueStyle = new Style { Color = ColorFromHTML("#B5CEA8") };
        private static readonly Style FunctionStyle = new Style { Color = ColorFromHTML("#DCDCAA") };

        /// <summary>
        /// The formatters with a document, so that those whose editor no longer shows them can
        /// close theirs (<see cref="CloseOrphans"/>). Used on the main thread.
        /// </summary>
        private static readonly HashSet<KotlinFormatter> WithDocuments = new HashSet<KotlinFormatter>();

        /// <summary>Bumped on every edit, so a compile can tell whether the code changed since it began.</summary>
        private int _version;

        /// <summary>
        /// The <see cref="_version"/> whose text the daemon has (as far as the messages queued
        /// say), so a compile of the document is a compile of that version.
        /// </summary>
        private volatile int _syncedVersion = -1;

        /// <summary>The code as of the latest edit.</summary>
        private string _text;

        /// <summary>The latest compile's response and the <see cref="_version"/> it compiled, swapped together.</summary>
        private volatile Compiled _compiled;

        private Editor _preview;

        /// <summary>This editor's program on the daemon; made when it is first sent.</summary>
        private LspDocument _document;

        /// <summary>Set once this formatter's editor no longer shows it, after which it sends nothing.</summary>
        private bool _closed;

        /// <summary>The document's completion requests, one at a time; used once there is a document.</summary>
        private readonly CompletionRequests _completionRequests;

        private sealed class Compiled
        {
            public readonly int Version;

            /// <summary>Null for IC10 read back from a chip, compiled with whichever setting it was.</summary>
            public readonly bool? Compact;

            public readonly CompileResponse Response;

            /// <summary>
            /// Why the compiler couldn't compile <see cref="Version"/>, when IC10 read back from a
            /// chip was kept in place of that answer.
            /// </summary>
            public readonly string Unavailable;

            public Compiled(int version, bool? compact, CompileResponse response, string unavailable = null)
            {
                Version = version;
                Compact = compact;
                Response = response;
                Unavailable = unavailable;
            }
        }

        public KotlinFormatter()
        {
            Identifier = new VersionedTextDocumentIdentifier { uri = null, version = 0 };
            _completionRequests = new CompletionRequests(
                (line, column) => _document.Complete(line, column),
                (request, completions) => ShowCompletions(request, completions).Forget());
            OnCodeChanged += () =>
            {
                // The editor signals some changes twice (a reset, then again on its next frame), so
                // only a change to the text counts as an edit.
                string text = RawText;
                bool edited = text != _text;
                if (edited)
                {
                    _text = text;
                    Interlocked.Increment(ref _version);
                    // ICodeFormatter's own handler measured the code before the version changed.
                    UpdateCodeSize();
                }
                SubmitChanges();
                if (edited)
                    CompileSoon().Forget();
            };
            OnCaretMoved += HighlightPreview;
        }

        /// <summary>
        /// Highlights the Kotlin keywords and the SDK's names. A name after a dot is a member,
        /// told apart by the name before the dot: an enum's value (<c>Color.Red</c>), a family's
        /// prefab (<c>Batteries.Small</c>) or a logic type (<c>d0.Temperature</c>). Anywhere else it
        /// is a type, an enum, a device or a function, so <c>db.Color = Color.Red</c> colors each
        /// <c>Color</c> as what it is there. The editor parses one line at a time, so a member
        /// starting a continuation line (<c>.Small</c> below <c>Batteries</c>) has no name before
        /// its dot and is colored by its name alone.
        /// </summary>
        public override StyledLine ParseLine(string lineText)
        {
            var line = Tokenizer.ParseLine(lineText);
            Token qualifier = null;
            Token previous = null;
            foreach (var token in line)
            {
                if (!token.Text.StartsWith(CommentPrefix) && !KotlinKeywords.ContainsKey(token.Text))
                {
                    var kind = previous?.Text == "."
                        ? SdkNameKinds.Member(qualifier?.Text, token.Text)
                        : SdkNameKinds.Name(token.Text);
                    var style = StyleOf(kind);
                    if (style.HasValue)
                        token.Style = style.Value;
                }
                qualifier = previous;
                previous = token;
            }
            return line;
        }

        private static Style? StyleOf(NameKind kind)
        {
            switch (kind)
            {
                case NameKind.Type:
                    return TypeStyle;
                case NameKind.Enum:
                    return EnumStyle;
                case NameKind.EnumValue:
                    return EnumValueStyle;
                case NameKind.Device:
                    return DeviceStyle;
                case NameKind.LogicType:
                    return LogicTypeStyle;
                case NameKind.Function:
                    return FunctionStyle;
                default:
                    return null;
            }
        }

        /// <summary>
        /// How sure the IC10 Editor can be that <paramref name="input"/> is a KTrapIC program, which
        /// it uses to pick a language for code it opens. A chip exported from here, or a program
        /// starting with <c>import ktrapic</c>, is certain; otherwise the score is the share of lines
        /// that look like Kotlin, below what IC10 scores for IC10.
        /// </summary>
        public static double MatchingScore(string input)
        {
            if (string.IsNullOrWhiteSpace(input))
                return 0;
            if (input.Contains("# " + SourceTag + ":"))
                return 2;
            var lines = input.Split('\n').Select(line => line.Trim()).Where(line => line.Length > 0).ToList();
            if (lines[0].StartsWith("import ktrapic"))
                return 2;
            string[] kotlin = { "val ", "var ", "fun ", "if (", "while (", "for (", "when ", "//", "}", "import " };
            double looksLikeKotlin = lines.Count(line => kotlin.Any(line.StartsWith) || line.EndsWith("{"));
            return 0.9 * looksLikeKotlin / lines.Count;
        }

        /// <summary>
        /// Shows <paramref name="code"/>, or the Kotlin source encoded in it. The IC10 before an
        /// encoded source is that source compiled, so it stands as the compile result until the
        /// source is edited: the editor checks the chip's limits against it, and an unchanged
        /// program exports as it is, even before the compiler has started.
        /// </summary>
        public override void ResetCode(string code)
        {
            string source = ExtractEncodedSource(code, SourceTag);
            if (string.IsNullOrEmpty(source))
            {
                base.ResetCode(code);
                return;
            }
            base.ResetCode(source);
            string ic10 = code.Replace("\r", "");
            ic10 = ic10.Substring(0, ic10.IndexOf("# " + SourceTag + ":", StringComparison.Ordinal)).TrimEnd('\n');
            // A program that didn't compile was exported as a comment saying why, which isn't a result.
            if (ic10.StartsWith(ErrorPrefix, StringComparison.Ordinal) || _compiled?.Version == _version)
                return;
            var lines = ic10.Split('\n');
            var response = new CompileResponse
            {
                code = ic10,
                num_lines = lines.Length,
                // Counted as the compiler counts it (ProgramSize.of): line breaks before the last
                // non-empty line count 2, as the game's editor joins lines with CRLF.
                num_bytes = lines.Sum(line => line.Length) + 2 * Math.Max(0, Array.FindLastIndex(lines, line => line.Length > 0)),
            };
            _compiled = new Compiled(_version, null, response);
            UpdateCodeSize();
        }

        /// <summary>
        /// Sends the edits made since the last call to the daemon, opening the document there
        /// first if it isn't open (the first time, or on a daemon that has started again since).
        /// Nothing is sent for a read-only view, such as a library's in the Library window, or
        /// until the daemon is initialized; the edits are kept until then.
        /// </summary>
        public override void SubmitChanges()
        {
            if (!HasDocument())
            {
                _changes.Clear();
                return;
            }
            var client = Daemon.Client;
            if (client == null || !client.Connection.IsInitialized)
                return;
            if (_document.Connection != client.Connection)
            {
                LspClient = client;
                _document.Bind(client.Connection);
                _completionRequests.Forget();
            }
            string text = RawText;
            if (_completionRequests.Sync(text, () => _document.Sync(Identifier.version, () => text, JArray.FromObject(_changes))))
            {
                _changes.Clear();
                _syncedVersion = _version;
            }
        }

        /// <summary>
        /// Removes line <paramref name="index"/>, sending the daemon the change that removes it
        /// there too (<see cref="LspDocument.LineRemoval"/>): the base class's would leave the
        /// line break before a last line behind.
        /// </summary>
        public override void RemoveLine(int index)
        {
            IncrementVersion();
            _changes.Add(
                LspDocument.LineRemoval(index, Lines.Count, i => Lines[i].Text.Length)
                    .ToObject<TextDocumentContentChangeEvent>());
            Lines.RemoveAt(index);
        }

        /// <summary>
        /// Asks the daemon what could be written at the caret, after a word that has begun or
        /// right after a dot (<see cref="Completions.Wanted"/>), and shows the answer once it
        /// comes, if the code and the caret haven't changed since. Requests go one at a time, the
        /// latest wanted after the one in flight if the code is still what it was made in
        /// (<see cref="CompletionRequests"/>). The editor calls this when the
        /// code changes or the caret moves, with the caret at the end of a word or a line.
        /// </summary>
        public override void UpdateAutocomplete()
        {
            _autocomplete = null;
            _autocompleteInsertText = null;
            var caret = Editor.CaretPos;
            if (caret.Line >= Lines.Count)
                return;
            string line = Lines[caret.Line].Text;
            if (!Completions.Wanted(line, caret.Col))
                return;
            SubmitChanges();
            if (_document == null || _syncedVersion != _version)
                return;
            // Told apart by their text rather than _version: the editor's first call for an edit
            // comes before the handler that counts the edit in _version has run.
            _completionRequests.Want(
                new CompletionRequest(RawText, caret.Line, caret.Col, Completions.WordBefore(line, caret.Col)));
        }

        private async UniTaskVoid ShowCompletions(CompletionRequest request, IReadOnlyList<Completion> completions)
        {
            await UniTask.SwitchToMainThread();
            var now = Editor.CaretPos;
            if (_closed || now.Line != request.Line || now.Col != request.Column || request.Text != RawText)
                return;
            var choice = Completions.Choose(completions, request.Word);
            if (choice == null)
                return;
            var popup = new StyledText();
            foreach (var completion in choice.Shown)
            {
                var entry = new StyledLine(completion.label);
                entry.Add(new Token(0, completion.label, CompletionStyle(completion.kind)));
                popup.Add(entry);
            }
            if (choice.Matched > choice.Shown.Count)
                popup.AddLine($"... and {choice.Matched - choice.Shown.Count} more", DefaultStyle);
            _autocomplete = popup;
            _autocompleteInsertText = choice.Insert;
        }

        /// <summary>The color of a completion of LSP <c>CompletionItemKind</c> <paramref name="kind"/>, as the code colors its names.</summary>
        private static Style CompletionStyle(int kind)
        {
            switch (kind)
            {
                case 3: // Function
                    return FunctionStyle;
                case 6: // Variable
                case 10: // Property
                    return LogicTypeStyle;
                case 7: // Class
                case 8: // Interface
                case 9: // Module: an object
                    return TypeStyle;
                case 13: // Enum
                    return EnumStyle;
                case 20: // EnumMember
                    return EnumValueStyle;
                default:
                    return DefaultStyle;
            }
        }

        /// <summary>
        /// Inserts the shown completions' shared text, only while they're shown: the editor keeps
        /// the text to insert after it stops showing them.
        /// </summary>
        public override void PerformAutocomplete()
        {
            if (_autocomplete != null)
                base.PerformAutocomplete();
        }

        /// <summary>
        /// Whether this editor's program is to be a document on the daemon, making the document
        /// the first time: only for an editable editor in a tab, and only while the editor still
        /// shows this formatter.
        /// </summary>
        private bool HasDocument()
        {
            if (_closed || Editor == null || Editor.IsReadOnly || Editor.ParentTab == null)
                return false;
            CloseOrphans();
            if (_closed)
                return false;
            if (_document == null)
            {
                _document = new LspDocument(LspDocument.NewUri(Editor.FileName));
                Identifier.uri = _document.Uri;
                _document.Diagnostics += OnDiagnostics;
                Daemon.Ready += OnDaemonReady;
                WithDocuments.Add(this);
            }
            return true;
        }

        /// <summary>
        /// Whether this formatter's editor has stopped showing it: the editor picked another
        /// formatter for code it loaded, its tab was closed, or its window was dropped. The IC10
        /// Editor drops a chip's window, tabs and all, once the chip is gone or the game has left
        /// the save (to the menu, or to load another); every window, the one the Library opens
        /// scripts in too, is in <see cref="IC10EditorPatches.AllEditors"/> until then.
        /// </summary>
        private bool IsOrphaned
        {
            get
            {
                if (Editor == null || Editor.CodeFormatter != this)
                    return true;
                var window = Editor.ParentTab?.ParentWindow;
                return window != null
                    && (!window.Tabs.Contains(Editor.ParentTab) || !IC10EditorPatches.AllEditors.Contains(window));
            }
        }

        /// <summary>
        /// Closes the documents of formatters no longer shown, which the editor drops without
        /// telling them.
        /// </summary>
        private static void CloseOrphans()
        {
            foreach (var formatter in WithDocuments.Where(f => f.IsOrphaned).ToList())
                formatter.CloseDocument();
        }

        private void CloseDocument()
        {
            _closed = true;
            if (_document == null)
                return;
            WithDocuments.Remove(this);
            Daemon.Ready -= OnDaemonReady;
            _document.Diagnostics -= OnDiagnostics;
            _document.Close();
        }

        /// <summary>
        /// A daemon has started (again): opens the document on it, and compiles, so the preview and
        /// the error marks come back.
        /// </summary>
        private void OnDaemonReady(DaemonLspClient client)
        {
            if (IsOrphaned)
            {
                CloseDocument();
                return;
            }
            SubmitChanges();
            CompileSoon().Forget();
        }

        private void OnDiagnostics(JObject parameters) => ApplyDiagnostics(parameters).Forget();

        /// <summary>
        /// Marks the errors of a version of the document, if it is still the current one: an
        /// older version's would mark the wrong places, and the current one's follow. The IC10
        /// Editor marks errors only, not warnings, and replaces all the marks each time.
        /// </summary>
        private async UniTaskVoid ApplyDiagnostics(JObject parameters)
        {
            await UniTask.SwitchToMainThread();
            if (_closed || (int?)parameters["version"] != Identifier.version || _changes.Count > 0)
                return;
            UpdateDiagnostics(new PublishDiagnosticsParams
            {
                diagnostics = parameters["diagnostics"]?.ToObject<Diagnostic[]>() ?? Array.Empty<Diagnostic>(),
            });
            UpdateStatus();
        }

        /// <summary>
        /// The IC10 to write to the chip, followed by the encoded Kotlin source. The editor exports on
        /// the main thread and takes the answer at once, so this uses the latest compile if it's of
        /// the current code, and otherwise compiles here, waiting for the compiler. If the program
        /// doesn't compile, the chip gets the error as a comment (and runs nothing), still with the
        /// source.
        /// </summary>
        public override string Compile()
        {
            string source = RawText;
            bool compact = Plugin.Compact.Value;
            var compiled = _compiled;
            var response =
                compiled != null && compiled.Version == _version && (compiled.Compact ?? compact) == compact
                    ? compiled.Response
                    : CompileNow(compact);
            string code;
            if (response == null)
                code = ErrorPrefix + "the compiler is still starting; export again in a moment.";
            else if (response.error != null)
                code = ErrorPrefix + Describe(response.error).Replace("\n", "\n# ");
            else
                code = response.code;
            return code + "\n" + EncodeSource(source, SourceTag);
        }

        /// <summary>
        /// Compiles the current code on this thread, once its last edits are sent; null if the
        /// daemon isn't running and initialized with the document open, as waiting for it to start
        /// would freeze the game.
        /// </summary>
        private CompileResponse CompileNow(bool compact)
        {
            SubmitChanges();
            var client = Daemon.Client;
            if (_document == null || client == null || _document.Connection != client.Connection || _syncedVersion != _version)
                return null;
            return _document.CompileNow(compact, ExportTimeout);
        }

        /// <summary>
        /// The size the editor checks against the chip's limits: the compiled IC10's, once the current
        /// code has compiled (or when it was read back from a chip with its IC10), as the IC10
        /// language reports its minified code's; until then the Kotlin source's. The encoded source
        /// appended on export isn't counted, as the chip skips comment lines.
        /// </summary>
        public override void UpdateCodeSize()
        {
            var compiled = _compiled;
            var response = compiled?.Response;
            if (compiled == null || compiled.Version != _version || response.error != null || response.code == null)
            {
                base.UpdateCodeSize();
                return;
            }
            CodeSize.NumLines = response.num_lines;
            CodeSize.NumBytes = response.num_bytes;
            CodeSize.MaxLineLength = response.code.Split('\n').Max(line => line.Length);
        }

        public override void DrawButtons()
        {
            bool compact = Plugin.Compact.Value;
            if (ImGuiUtils.Checkbox("Compact", ref compact, Plugin.Compact.Description.Description))
            {
                Plugin.Compact.Value = compact;
                int version = Interlocked.Increment(ref _version);
                // The text didn't change, so the daemon still has it if it had it before.
                if (_syncedVersion == version - 1)
                    _syncedVersion = version;
                CompileSoon().Forget();
            }
            ImGui.SameLine();
            ImGui.TextUnformatted(StatusText());
        }

        private string StatusText()
        {
            if (Runtime.Progress != null)
                return Runtime.Progress + "...";
            var compiled = _compiled;
            var response = compiled?.Response;
            // IC10 read back from a chip doesn't say how many registers it uses.
            if (response == null || compiled.Compact == null)
                return Daemon.StartError
                    ?? (compiled?.Version == _version ? compiled.Unavailable : null)
                    ?? "Compiling...";
            if (response.error != null)
                return "Doesn't compile";
            return $"{response.num_lines} lines, {response.num_bytes} bytes, {response.num_registers} registers";
        }

        private static string Describe(CompileResponse.Problem problem) =>
            problem.line > 0
                ? $"line {problem.line}, column {problem.column}: {problem.description}"
                : problem.description;

        /// <summary>
        /// Compiles the code once edits pause, then shows the result. If the daemon isn't running,
        /// this starts it instead; once it is ready, <see cref="OnDaemonReady"/> compiles.
        /// </summary>
        private async UniTaskVoid CompileSoon()
        {
            // A library's read-only view in the Library window isn't compiled.
            if (Editor == null || Editor.IsReadOnly || _closed)
                return;
            int version = _version;
            bool compact = Plugin.Compact.Value;
            // Waited out on the thread pool, as UniTask's own delays count game time, which can
            // stand still while the editor is open.
            await UniTask.SwitchToThreadPool();
            await Task.Delay(DebounceMs);
            if (version != _version)
                return;
            var client = Daemon.Client;
            if (client == null || !client.Connection.IsRunning)
            {
                try
                {
                    client = await Daemon.Start();
                }
                catch (Exception e)
                {
                    await Show(version, compact, CompileResponse.Failed($"The KTrapIC compiler couldn't start: {e.Message}"));
                    return;
                }
                // Started by another editor, and ready before this one had a document: open it now.
                if (client.Connection.IsInitialized)
                {
                    await UniTask.SwitchToMainThread();
                    OnDaemonReady(client);
                }
                return;
            }
            // Not yet open on this daemon, or not sent yet: once it is, this compiles again.
            var document = _document;
            if (document == null || document.Connection != client.Connection || _syncedVersion != version)
                return;
            await Show(version, compact, await document.Compile(compact));
        }

        /// <summary>Records <paramref name="response"/>, a compile of <paramref name="version"/>, and shows it if that's still current.</summary>
        private async UniTask Show(int version, bool compact, CompileResponse response)
        {
            // Recorded before returning to the main thread, which Compile() may be blocking.
            if (version != _version)
                return;
            // IC10 read back from a chip stands until the compiler can say something about the
            // program, so an unchanged chip still exports as it is while the compiler is down; the
            // toolbar says why it's down.
            var kept = _compiled;
            if (response.Unavailable && kept != null && kept.Version == version && kept.Compact == null)
            {
                _compiled = new Compiled(kept.Version, null, kept.Response, response.error?.description);
                return;
            }
            _compiled = new Compiled(version, compact, response);
            await UniTask.SwitchToMainThread();
            if (version == _version)
            {
                UpdateCodeSize();
                ShowPreview(response);
            }
        }

        private Editor Preview
        {
            get
            {
                if (_preview == null)
                {
                    var tab = Editor.ParentTab;
                    tab.ClearExtraEditors();
                    _preview = new Editor(Editor.KeyHandler) { IsReadOnly = true };
                    tab.AddEditor(_preview);
                    _preview.CodeFormatter = new IC10CodeFormatter { Editor = _preview };
                }
                return _preview;
            }
        }

        private void ShowPreview(CompileResponse response)
        {
            if (Editor?.ParentTab == null)
                return;
            string text = response.error != null
                ? "# Doesn't compile, at " + Describe(response.error).Replace("\n", "\n# ")
                : response.code ?? "";
            var preview = Preview;
            preview.CaretPos = new TextPosition(0, 0);
            preview.CodeFormatter.ResetCode(text);
            HighlightPreview();
        }

        /// <summary>Highlights, in the preview, the IC10 lines that the caret's line compiled to.</summary>
        private void HighlightPreview()
        {
            if (_preview == null)
                return;
            // The IC10 formatter may style a line's color too, which is kept.
            var styles = _preview.CodeFormatter.LineStyles;
            foreach (var entry in styles.Where(entry => entry.Value.Background == PreviewHighlight).ToList())
            {
                if (entry.Value.Color == 0)
                    styles.Remove(entry.Key);
                else
                    styles[entry.Key] = new Style { Color = entry.Value.Color };
            }
            var mapping = _compiled?.Response?.source_mapping;
            if (mapping == null || !mapping.TryGetValue(CaretPos.Line, out var lines))
                return;
            foreach (int line in lines)
            {
                uint color = styles.TryGetValue(line, out var style) ? style.Color : 0;
                styles[line] = new Style { Color = color, Background = PreviewHighlight };
            }
        }
    }
}
