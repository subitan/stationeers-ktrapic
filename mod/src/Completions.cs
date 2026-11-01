using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;

namespace KTrapIC
{
    /// <summary>
    /// One of the daemon's completions (an LSP <c>CompletionItem</c>): a name that could go at the
    /// caret, what kind of thing it is (an LSP <c>CompletionItemKind</c>), its declaration (such as
    /// <c>var Setting: Double</c>), where it sorts, and, for an SDK type, what it is also found by:
    /// its label and the meaningful words of the game's names of its prefabs, space-separated
    /// (<c>LedDisplay ConsoleLED5 ...</c>, from <c>StructureConsoleLED5</c>).
    /// </summary>
    public sealed class Completion
    {
        public string label;
        public int kind;
        public string detail;
        public string sortText;
        public string filterText;
    }

    /// <summary>
    /// What the completion popup shows for the word being typed: at most <see cref="Completions.MaxShown"/>
    /// of the completions that start with it and then of those found by their <c>filterText</c>
    /// (<see cref="Completions.Choose"/>), how many matched in all, and the text that accepting
    /// inserts, which is what those starting with the word share past it (the IC10 Editor inserts
    /// that, as it has no way to pick one). Null when nothing is worth showing.
    /// </summary>
    public sealed class CompletionChoice
    {
        public IReadOnlyList<Completion> Shown;
        public int Matched;
        public string Insert;
    }

    /// <summary>
    /// One document's completion requests (<see cref="Requests{T}"/>): one with no answer from
    /// the daemon is answered with no completions.
    /// </summary>
    public sealed class CompletionRequests : Requests<IReadOnlyList<Completion>>
    {
        /// <param name="complete">
        /// Asks the daemon for the completions at a line and column of the document as of the
        /// last <see cref="Requests{T}.Sync"/>, sending the request before it returns: null if it
        /// gives no answer.
        /// </param>
        /// <param name="answered">Given each request sent, or answered again, with its completions.</param>
        public CompletionRequests(
            Func<int, int, Task<IReadOnlyList<Completion>>> complete,
            Action<CaretRequest, IReadOnlyList<Completion>> answered)
            : base(complete, answered, Array.Empty<Completion>())
        {
        }
    }

    /// <summary>The game-independent part of completion in the Kotlin editor.</summary>
    public static class Completions
    {
        /// <summary>How many completions the popup lists.</summary>
        public const int MaxShown = 20;

        /// <summary>
        /// How long the word typed must be for completions to be found by their <c>filterText</c>:
        /// a shorter one would find most SDK types. The daemon offers family members by their game
        /// names from the same length.
        /// </summary>
        public const int FilterTextMinLength = 3;

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
            return AfterDot(line, column);
        }

        /// <summary>
        /// Whether the caret at <paramref name="column"/> is right after a dot that follows a name
        /// or a closing bracket (<c>d0.</c>, <c>f().</c>), not one in a number or a range
        /// (<c>1.</c>, <c>0..</c>).
        /// </summary>
        public static bool AfterDot(string line, int column)
        {
            column = Math.Min(column, line.Length);
            if (column < 2 || line[column - 1] != '.')
                return false;
            char before = line[column - 2];
            if (before == ')' || before == ']')
                return true;
            string word = WordBefore(line, column - 1);
            return word.Length > 0 && !char.IsDigit(word[0]);
        }

        /// <summary>
        /// The completions to show for <paramref name="word"/>: those starting with it (as typed,
        /// case and all), in the daemon's order, then, for a word of at least
        /// <see cref="FilterTextMinLength"/> letters, those found by their <c>filterText</c>
        /// instead: the SDK types with a word of their name or their game names starting with it,
        /// ignoring case (<c>LedDisplay</c> for <c>Console</c>, from <c>ConsoleLED5</c>; see
        /// <see cref="WordStarts"/>). Not those starting with <c>_</c>. Null if none match, or
        /// the only one is the word itself. What accepting inserts is what those starting with the
        /// word share past it: the editor can only add to the word, not replace it.
        /// </summary>
        public static CompletionChoice Choose(IEnumerable<Completion> completions, string word)
        {
            var named = new List<Completion>();
            var found = new List<Completion>();
            foreach (var c in completions.OrderBy(c => c.sortText ?? "", StringComparer.Ordinal))
            {
                if (string.IsNullOrEmpty(c.label) || c.label.StartsWith("_"))
                    continue;
                if (c.label.StartsWith(word, StringComparison.Ordinal))
                    named.Add(c);
                else if (word.Length >= FilterTextMinLength && c.filterText != null && WordStarts(c.filterText, word))
                    found.Add(c);
            }
            var matching = named.Concat(found).ToList();
            if (matching.Count == 0 || (matching.Count == 1 && matching[0].label == word))
                return null;
            string shared = named.Count > 0 ? named[0].label : "";
            foreach (var completion in named)
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

        /// <summary>
        /// Whether a word of <paramref name="text"/> starts with <paramref name="word"/>, ignoring
        /// case. A word starts at the start of the text, at a letter or digit after anything else
        /// (a space, a dot), at a capital after a lower-case letter or a digit, at the last capital
        /// of a run of them followed by a lower-case letter (<c>Silo</c> in <c>SDBSilo</c>), or at
        /// a digit after a letter. <c>Console</c>, <c>consoleLED</c> and <c>led</c> start words of
        /// <c>LedDisplay ConsoleLED5</c>; <c>onsole</c> and <c>ED5</c> don't. The daemon's
        /// <c>K2Completion.wordStarts</c> is the same rule.
        /// </summary>
        public static bool WordStarts(string text, string word)
        {
            if (word.Length == 0)
                return false;
            for (int at = 0; at + word.Length <= text.Length; at++)
            {
                if (WordStartsAt(text, at) && string.Compare(text, at, word, 0, word.Length, StringComparison.OrdinalIgnoreCase) == 0)
                    return true;
            }
            return false;
        }

        private static bool WordStartsAt(string text, int at)
        {
            char c = text[at];
            if (!char.IsLetterOrDigit(c))
                return false;
            if (at == 0)
                return true;
            char before = text[at - 1];
            if (!char.IsLetterOrDigit(before))
                return true;
            if (char.IsDigit(c))
                return char.IsLetter(before);
            if (!char.IsUpper(c))
                return false;
            return !char.IsUpper(before) || (at + 1 < text.Length && char.IsLower(text[at + 1]));
        }
    }
}
