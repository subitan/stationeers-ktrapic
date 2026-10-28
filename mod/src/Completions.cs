using System;
using System.Collections.Generic;
using System.Linq;

namespace KTrapIC
{
    /// <summary>
    /// One of the daemon's completions (an LSP <c>CompletionItem</c>): a name that could go at the
    /// caret, what kind of thing it is (an LSP <c>CompletionItemKind</c>), its declaration (such as
    /// <c>var Setting: Double</c>), and where it sorts.
    /// </summary>
    public sealed class Completion
    {
        public string label;
        public int kind;
        public string detail;
        public string sortText;
    }

    /// <summary>
    /// What the completion popup shows for the word being typed: at most <see cref="Completions.MaxShown"/>
    /// of the completions that start with it, how many matched in all, and the text that accepting
    /// inserts, which is what all of them share past the word (the IC10 Editor inserts that, as it
    /// has no way to pick one). Null when nothing is worth showing.
    /// </summary>
    public sealed class CompletionChoice
    {
        public IReadOnlyList<Completion> Shown;
        public int Matched;
        public string Insert;
    }

    /// <summary>The game-independent part of completion in the Kotlin editor.</summary>
    public static class Completions
    {
        /// <summary>How many completions the popup lists.</summary>
        public const int MaxShown = 20;

        /// <summary>The identifier that ends at <paramref name="column"/> of <paramref name="line"/>, or "".</summary>
        public static string WordBefore(string line, int column)
        {
            column = Math.Min(column, line.Length);
            int start = column;
            while (start > 0 && (char.IsLetterOrDigit(line[start - 1]) || line[start - 1] == '_'))
                start--;
            return line.Substring(start, column - start);
        }

        /// <summary>
        /// Whether to ask for completions with the caret at <paramref name="column"/>: after a
        /// word that has begun, or right after a dot (<c>d0.</c>). An empty word elsewhere would
        /// list every name there is.
        /// </summary>
        public static bool Wanted(string line, int column)
        {
            column = Math.Min(column, line.Length);
            string word = WordBefore(line, column);
            if (word.Length > 0)
                return !char.IsDigit(word[0]);
            return column > 0 && line[column - 1] == '.';
        }

        /// <summary>
        /// The completions to show for <paramref name="word"/>: those starting with it (as typed,
        /// case and all), in the daemon's order, and not those starting with <c>_</c>. Null if
        /// none match, or the only one is the word itself.
        /// </summary>
        public static CompletionChoice Choose(IEnumerable<Completion> completions, string word)
        {
            var matching = completions
                .Where(c => !string.IsNullOrEmpty(c.label) && c.label.StartsWith(word, StringComparison.Ordinal) && !c.label.StartsWith("_"))
                .OrderBy(c => c.sortText ?? "", StringComparer.Ordinal)
                .ToList();
            if (matching.Count == 0 || (matching.Count == 1 && matching[0].label == word))
                return null;
            string shared = matching[0].label;
            foreach (var completion in matching)
            {
                int length = 0;
                while (length < shared.Length && length < completion.label.Length && shared[length] == completion.label[length])
                    length++;
                shared = shared.Substring(0, length);
            }
            return new CompletionChoice
            {
                Shown = matching.Take(MaxShown).ToList(),
                Matched = matching.Count,
                Insert = shared.Length > word.Length ? shared.Substring(word.Length) : null,
            };
        }
    }
}
