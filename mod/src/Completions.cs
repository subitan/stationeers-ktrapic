using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;

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

    /// <summary>Completions wanted: at a caret (0-based line and column) in a version of the code, after a word.</summary>
    public sealed class CompletionRequest
    {
        public readonly int Version;
        public readonly int Line;
        public readonly int Column;
        public readonly string Word;

        public CompletionRequest(int version, int line, int column, string word)
        {
            Version = version;
            Line = line;
            Column = column;
            Word = word;
        }

        /// <summary>Whether <paramref name="other"/> is at the same caret in the same version.</summary>
        public bool IsAt(CompletionRequest other) =>
            other != null && Version == other.Version && Line == other.Line && Column == other.Column;
    }

    /// <summary>
    /// One document's completion requests to the daemon, which answers them one at a time, each
    /// taking a full analysis of the program; typing outruns it. So at most one request is in
    /// flight: those wanted meanwhile wait, and when it is answered only the latest of them is
    /// sent, the others being out of date by then. A request at the caret and version of the one
    /// in flight is not sent again (the IC10 Editor asks twice for each keystroke), and one at
    /// those of the last answered gets that answer again.
    ///
    /// <para>Answers are given on the thread the daemon's answer arrives on, or, for one given
    /// again, on <see cref="Want"/>'s.</para>
    /// </summary>
    public sealed class CompletionRequests
    {
        private readonly Func<int, int, Task<IReadOnlyList<Completion>>> _complete;
        private readonly Action<CompletionRequest, IReadOnlyList<Completion>> _answered;
        private readonly object _lock = new object();
        private CompletionRequest _inFlight;
        private CompletionRequest _next;
        private CompletionRequest _lastAnswered;
        private IReadOnlyList<Completion> _lastAnswer;

        /// <param name="complete">Asks the daemon for the completions at a line and column.</param>
        /// <param name="answered">Given each request sent, or answered again, with its completions.</param>
        public CompletionRequests(
            Func<int, int, Task<IReadOnlyList<Completion>>> complete,
            Action<CompletionRequest, IReadOnlyList<Completion>> answered)
        {
            _complete = complete;
            _answered = answered;
        }

        /// <summary>Asks for completions at <paramref name="request"/>'s caret, now or once the request in flight is answered.</summary>
        public void Want(CompletionRequest request)
        {
            IReadOnlyList<Completion> answer = null;
            lock (_lock)
            {
                if (request.IsAt(_inFlight))
                {
                    _next = null;
                    return;
                }
                if (_inFlight != null)
                {
                    _next = request;
                    return;
                }
                if (request.IsAt(_lastAnswered))
                    answer = _lastAnswer;
                else
                    _inFlight = request;
            }
            if (answer != null)
                _answered(request, answer);
            else
                _ = Send(request);
        }

        private async Task Send(CompletionRequest request)
        {
            while (request != null)
            {
                IReadOnlyList<Completion> answer;
                try
                {
                    answer = await _complete(request.Line, request.Column).ConfigureAwait(false);
                }
                catch (Exception)
                {
                    answer = Array.Empty<Completion>();
                }
                CompletionRequest next;
                lock (_lock)
                {
                    _lastAnswered = request;
                    _lastAnswer = answer;
                    next = _next;
                    _next = null;
                    _inFlight = next;
                }
                _answered(request, answer);
                request = next;
            }
        }
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
