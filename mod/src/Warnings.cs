using System;
using System.Collections.Generic;
using System.Linq;

namespace KTrapIC
{
    /// <summary>A compile's warning that is marked in the code: its message, at a 0-based line and column.</summary>
    public sealed class PlacedWarning
    {
        public int Line;
        public int Column;
        public string Message;
    }

    /// <summary>
    /// A compile's warnings, sorted by where they're shown: those marked in the code, where the
    /// compiler places them, and the rest, with no place there (such as the program being over the
    /// chip's 128 lines) or nothing to mark at it, shown in the editor's status line; one of those
    /// with a place starts with it ("line 3, column 1: ...").
    /// </summary>
    public sealed class RoutedWarnings
    {
        public static readonly RoutedWarnings None =
            new RoutedWarnings(Array.Empty<PlacedWarning>(), Array.Empty<string>());

        public readonly IReadOnlyList<PlacedWarning> Placed;
        public readonly IReadOnlyList<string> Unplaced;

        public RoutedWarnings(IReadOnlyList<PlacedWarning> placed, IReadOnlyList<string> unplaced)
        {
            Placed = placed;
            Unplaced = unplaced;
        }

        public int Count => Placed.Count + Unplaced.Count;
    }

    /// <summary>
    /// A compile's warnings and the editor's tokens they mark, each token with the messages of the
    /// warnings on it. The warnings are kept with their places, so that when the editor makes new
    /// tokens for code that hasn't changed since the compile (it does on a reset to the same text),
    /// the marks are found again (<see cref="Remark"/>). A token the editor makes for an edited
    /// line has no mark, as it has no error, until the next compile. <typeparamref name="TToken"/>
    /// is told apart by reference, as the editor's tokens are.
    /// </summary>
    public sealed class WarningMarks<TToken>
        where TToken : class
    {
        private readonly Dictionary<TToken, List<string>> _marks = new Dictionary<TToken, List<string>>();
        private int _version = -1;

        /// <summary>The latest compile's warnings, as <see cref="Warnings.Route"/> sorted them.</summary>
        public RoutedWarnings Routed { get; private set; } = RoutedWarnings.None;

        /// <summary>Whether any token is marked.</summary>
        public bool Any => _marks.Count > 0;

        /// <summary>
        /// Takes the warnings of <paramref name="response"/>, a compile of the code's
        /// <paramref name="version"/>, in place of the last compile's, and marks the token that
        /// <paramref name="tokenAt"/> finds at each one's 0-based line and column, if any.
        /// </summary>
        public void Apply(CompileResponse response, int version, Func<int, int, TToken> tokenAt)
        {
            Routed = Warnings.Route(response, (line, column) => tokenAt(line, column) != null);
            _version = version;
            Mark(tokenAt);
        }

        /// <summary>
        /// Marks the tokens <paramref name="tokenAt"/> finds now at the warnings' places, in place
        /// of those marked before, if the code is still <paramref name="version"/>, the one the
        /// warnings are of; otherwise their places may have moved, and the marks are left as they are.
        /// </summary>
        public void Remark(int version, Func<int, int, TToken> tokenAt)
        {
            if (version == _version)
                Mark(tokenAt);
        }

        /// <summary>The messages of the warnings that mark <paramref name="token"/>; null if none do.</summary>
        public IReadOnlyList<string> At(TToken token) =>
            token != null && _marks.TryGetValue(token, out var messages) ? messages : null;

        private void Mark(Func<int, int, TToken> tokenAt)
        {
            _marks.Clear();
            foreach (var warning in Routed.Placed)
            {
                var token = tokenAt(warning.Line, warning.Column);
                if (token == null)
                    continue;
                if (!_marks.TryGetValue(token, out var messages))
                    _marks[token] = messages = new List<string>();
                messages.Add(warning.Message);
            }
        }
    }

    /// <summary>
    /// The game-independent part of showing the compiler's warnings in the Kotlin editor. The IC10
    /// Editor marks only errors (LSP severity 1) and drops the daemon's warnings (severity 2), so
    /// the plugin shows them itself, from its own compile of the program (<c>ktrapic/compile</c>,
    /// whose warnings say when they have no place: line 0).
    /// </summary>
    public static class Warnings
    {
        /// <summary>Starts a warning's message wherever it is shown, as an error's color tells it apart.</summary>
        public const string Prefix = "Warning: ";

        /// <summary>
        /// Sorts <paramref name="response"/>'s warnings: one at a 1-based line and column that
        /// <paramref name="placeable"/> says can be marked (given the 0-based line and column) is
        /// marked there; the others, those with no place (line 0) or at a place with nothing to
        /// mark, are shown in the status line, the latter with their place. A failed compile, or
        /// none, has no warnings.
        /// </summary>
        public static RoutedWarnings Route(CompileResponse response, Func<int, int, bool> placeable)
        {
            if (response?.warnings == null || response.error != null || response.warnings.Count == 0)
                return RoutedWarnings.None;
            var placed = new List<PlacedWarning>();
            var unplaced = new List<string>();
            foreach (var warning in response.warnings.Where(w => w != null))
            {
                string message = warning.description ?? "";
                int line = warning.line - 1;
                int column = Math.Max(0, warning.column - 1);
                if (line < 0)
                    unplaced.Add(message);
                else if (placeable(line, column))
                    placed.Add(new PlacedWarning { Line = line, Column = column, Message = message });
                else
                    unplaced.Add(Located(line, column, message));
            }
            return new RoutedWarnings(placed, unplaced);
        }

        /// <summary>
        /// A warning's <paramref name="message"/> as a hover shows it: prefixed with
        /// <see cref="Prefix"/>, each of its lines wrapped to <paramref name="width"/>.
        /// </summary>
        public static IReadOnlyList<string> HoverLines(string message, int width) =>
            (Prefix + message).Split('\n').SelectMany(paragraph => Wrap(paragraph, width)).ToList();

        /// <summary>
        /// The status line's one line: the first warning at the caret (<paramref name="atCaret"/>),
        /// or if there is none there the first with no place in the code, saying how many more of
        /// those there are; null when there are neither.
        /// </summary>
        /// <remarks>Those with no place in the code include any with nothing to mark at their place, which start with it.</remarks>
        public static string StatusLine(IReadOnlyList<string> atCaret, IReadOnlyList<string> unplaced)
        {
            if (atCaret != null && atCaret.Count > 0)
                return Prefix + OneLine(atCaret[0]);
            if (unplaced == null || unplaced.Count == 0)
                return null;
            string more = unplaced.Count > 1 ? $" (and {unplaced.Count - 1} more)" : "";
            return Prefix + OneLine(unplaced[0]) + more;
        }

        /// <summary>How many warnings there are, as the toolbar says it; null for none.</summary>
        public static string CountText(int count) =>
            count <= 0 ? null : count == 1 ? "1 warning" : $"{count} warnings";

        /// <summary>
        /// How many warnings the toolbar count's tooltip lists, which can't scroll, before saying
        /// how many more there are; as many as the IC10 Editor's completion list shows.
        /// </summary>
        public const int SummaryLimit = 20;

        /// <summary>
        /// The warnings as the toolbar's count lists them when the mouse is on it: those shown in
        /// the status line first, then the marked ones by place, each starting with where it is
        /// (1-based, as the preview gives an error's place), wrapped to <paramref name="width"/>;
        /// at most <see cref="SummaryLimit"/> of them, followed by how many more there are.
        /// </summary>
        public static IReadOnlyList<string> SummaryLines(RoutedWarnings warnings, int width)
        {
            var messages = warnings.Unplaced
                .Concat(warnings.Placed.OrderBy(w => w.Line).ThenBy(w => w.Column).Select(w => Located(w.Line, w.Column, w.Message)))
                .ToList();
            var lines = messages.Take(SummaryLimit).SelectMany(message => HoverLines(message, width)).ToList();
            if (messages.Count > SummaryLimit)
                lines.Add($"... and {messages.Count - SummaryLimit} more");
            return lines;
        }

        /// <summary><paramref name="message"/> after its 0-based <paramref name="line"/> and <paramref name="column"/>, given 1-based.</summary>
        private static string Located(int line, int column, string message) =>
            $"line {line + 1}, column {column + 1}: {message}";

        private static string OneLine(string message) => message.Replace("\r", "").Replace('\n', ' ');

        /// <summary>
        /// <paramref name="text"/>'s words in lines of at most <paramref name="width"/> characters,
        /// but for a word longer than that, which has a line of its own.
        /// </summary>
        public static IEnumerable<string> Wrap(string text, int width)
        {
            var line = "";
            foreach (string word in text.Split(' '))
            {
                if (line.Length > 0 && line.Length + 1 + word.Length > width)
                {
                    yield return line;
                    line = "";
                }
                line = line.Length == 0 ? word : line + " " + word;
            }
            yield return line;
        }
    }
}
