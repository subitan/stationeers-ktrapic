using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Cysharp.Threading.Tasks;
using ImGuiNET;
using StationeersIC10Editor;
using StationeersIC10Editor.IC10;

namespace KTrapIC
{
    /// <summary>
    /// The IC10 Editor's "Kotlin" language: KTrapIC programs, highlighted, compiled as they're
    /// edited, with the IC10 shown in a read-only editor beside them and the IC10 lines of the
    /// caret's line highlighted there. Exporting writes the IC10 to the chip with the Kotlin
    /// source appended as encoded comments, which <see cref="ResetCode"/> reads back, so reopening
    /// the chip shows the Kotlin.
    /// </summary>
    public class KotlinFormatter : StaticFormatter
    {
        /// <summary>The tag of the Kotlin source encoded in the comments after the IC10.</summary>
        private const string SourceTag = "KTRAPIC_SOURCE";

        /// <summary>Starts the comment exported in place of a program that couldn't be compiled.</summary>
        private const string ErrorPrefix = "# KTrapIC: ";

        private const int DebounceMs = 150;

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

        private static readonly uint PreviewHighlight = ColorFromHTML("#000080");

        // The runtime's names, in the colors the editor's theme gives types, enums, variables,
        // properties, enum members and functions.
        private static readonly Style TypeStyle = new Style { Color = ColorFromHTML("#4EC9B0") };
        private static readonly Style EnumStyle = new Style { Color = ColorFromHTML("#B8D7A3") };
        private static readonly Style DeviceStyle = new Style { Color = ColorFromHTML("#9CDCFE") };
        private static readonly Style LogicTypeStyle = new Style { Color = ColorFromHTML("#9CDCFE") };
        private static readonly Style EnumValueStyle = new Style { Color = ColorFromHTML("#B5CEA8") };
        private static readonly Style FunctionStyle = new Style { Color = ColorFromHTML("#DCDCAA") };

        /// <summary>Bumped on every edit, so a compile can tell whether the code changed since it began.</summary>
        private int _version;

        /// <summary>The code as of the latest edit.</summary>
        private string _text;

        /// <summary>The latest compile's response and the <see cref="_version"/> it compiled, swapped together.</summary>
        private volatile Compiled _compiled;

        private Editor _preview;

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
            : base(
                TokenSeparators: " \t()[]{}:.,;+-*/%=<>!&|^~?@",
                StringDelimiters: "\"'",
                CommentPrefix: "//",
                Keywords: KotlinKeywords,
                KeepWhitespaces: false)
        {
            OnCodeChanged += () =>
            {
                // The editor signals some changes twice (a reset, then again on its next frame), so
                // only a change to the text counts as an edit.
                string text = RawText;
                if (text == _text)
                    return;
                _text = text;
                Interlocked.Increment(ref _version);
                // ICodeFormatter's own handler measured the code before the version changed.
                UpdateCodeSize();
                CompileSoon().Forget();
            };
            OnCaretMoved += HighlightPreview;
        }

        /// <summary>
        /// Highlights the Kotlin keywords and the runtime's names. A name after a dot is a member,
        /// told apart by the name before the dot: an enum's value (<c>Color.Red</c>), a family's
        /// prefab (<c>Batteries.Small</c>) or a logic type (<c>d0.Temperature</c>). Anywhere else it
        /// is a type, an enum, a device or a function, so <c>db.Color = Color.Red</c> colors each
        /// <c>Color</c> as what it is there. The editor parses one line at a time, so a member
        /// starting a continuation line (<c>.Small</c> below <c>Batteries</c>) has no name before
        /// its dot and is colored by its name alone.
        /// </summary>
        public override StyledLine ParseLine(string lineText)
        {
            var line = base.ParseLine(lineText);
            Token qualifier = null;
            Token previous = null;
            foreach (var token in line)
            {
                if (!token.Text.StartsWith(CommentPrefix) && !KotlinKeywords.ContainsKey(token.Text))
                {
                    var kind = previous?.Text == "."
                        ? RuntimeNameKinds.Member(qualifier?.Text, token.Text)
                        : RuntimeNameKinds.Name(token.Text);
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
                    : Daemon.CompileNow(source, compact);
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
                Interlocked.Increment(ref _version);
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

        /// <summary>Compiles the code once edits pause, then shows the result.</summary>
        private async UniTaskVoid CompileSoon()
        {
            // A library's read-only view in the Library window isn't compiled.
            if (Editor == null || Editor.IsReadOnly)
                return;
            int version = _version;
            string source = RawText;
            bool compact = Plugin.Compact.Value;
            // Waited out on the thread pool, as UniTask's own delays count game time, which can
            // stand still while the editor is open.
            await UniTask.SwitchToThreadPool();
            await Task.Delay(DebounceMs);
            if (version != _version)
                return;
            CompileResponse response;
            try
            {
                response = await Daemon.Compile(source, compact);
            }
            catch (Exception e)
            {
                Log.Error($"Compiling failed: {e}");
                response = CompileResponse.Failed($"Compiling failed: {e.Message}");
            }
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
