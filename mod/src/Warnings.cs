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
    /// chip's 128 lines), shown in the editor's status line.
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
        /// mark, are shown in the status line. A failed compile, or none, has no warnings.
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
                if (line >= 0 && placeable(line, column))
                    placed.Add(new PlacedWarning { Line = line, Column = column, Message = message });
                else
                    unplaced.Add(message);
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
        /// Every warning, as the toolbar's count shows them when the mouse is on it: those with no
        /// place first, then the others by place, each starting with where it is (1-based, as the
        /// preview gives an error's place), wrapped to <paramref name="width"/>.
        /// </summary>
        public static IReadOnlyList<string> SummaryLines(RoutedWarnings warnings, int width)
        {
            var lines = new List<string>();
            foreach (string message in warnings.Unplaced)
                lines.AddRange(HoverLines(message, width));
            foreach (var warning in warnings.Placed.OrderBy(w => w.Line).ThenBy(w => w.Column))
                lines.AddRange(HoverLines($"line {warning.Line + 1}, column {warning.Column + 1}: {warning.Message}", width));
            return lines;
        }

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
