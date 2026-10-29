using System;
using System.Threading.Tasks;

namespace KTrapIC
{
    /// <summary>
    /// A question for the daemon about a place in the code, whose text the daemon has: at a
    /// position (0-based line and column), after a word (the one the caret ends, for completion).
    /// </summary>
    public sealed class CaretRequest
    {
        public readonly string Text;
        public readonly int Line;
        public readonly int Column;
        public readonly string Word;

        public CaretRequest(string text, int line, int column, string word = "")
        {
            Text = text;
            Line = line;
            Column = column;
            Word = word;
        }

        /// <summary>Whether <paramref name="other"/> is at the same position in the same text.</summary>
        public bool IsAt(CaretRequest other) =>
            other != null && Line == other.Line && Column == other.Column && string.Equals(Text, other.Text, StringComparison.Ordinal);
    }

    /// <summary>
    /// One document's requests of one kind to the daemon (completions, hovers, signatures), which
    /// answers them one at a time, each taking a full analysis of the program; typing, or moving
    /// the mouse, outruns it. So at most one request is in flight: those wanted meanwhile wait,
    /// and when it is answered only the latest of them is sent, the others being out of date by
    /// then. A request at the position and text of the one in flight is not sent again (the IC10
    /// Editor asks twice for each keystroke), and one at those of the last answered gets that
    /// answer again. A request the daemon gave no answer to (see <see cref="LspDocument.Ask"/>) is
    /// answered with the "none" answer, which is not given again.
    ///
    /// <para>The daemon answers a request for the text it has when the request arrives, which
    /// only <see cref="Sync"/> changes. So a request is sent only while that text is the
    /// request's own, and one waiting is dropped when the text changes: it is neither sent nor
    /// answered. Every answer is thus of its request's text, and is given again only in that
    /// text.</para>
    ///
    /// <para>Answers are given on the thread the daemon's answer arrives on, or, for one given
    /// again, on <see cref="Want"/>'s.</para>
    /// </summary>
    public class Requests<T> where T : class
    {
        private readonly Func<int, int, Task<T>> _ask;
        private readonly Action<CaretRequest, T> _answered;
        private readonly T _none;
        private readonly object _lock = new object();
        private string _synced;
        private CaretRequest _inFlight;
        private CaretRequest _next;
        private CaretRequest _lastAnswered;
        private T _lastAnswer;

        /// <param name="ask">
        /// Asks the daemon about a line and column of the document as of the last
        /// <see cref="Sync"/>, sending the request before it returns: null if it gives no answer.
        /// </param>
        /// <param name="answered">Given each request sent, or answered again, with its answer.</param>
        /// <param name="none">The answer given for a request the daemon gave no answer to.</param>
        public Requests(Func<int, int, Task<T>> ask, Action<CaretRequest, T> answered, T none)
        {
            _ask = ask;
            _answered = answered;
            _none = none;
        }

        /// <summary>Asks about <paramref name="request"/>'s position, now or once the request in flight is answered.</summary>
        public void Want(CaretRequest request)
        {
            T answer = null;
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

        /// <summary>
        /// Brings the daemon's copy of the document to <paramref name="text"/> with
        /// <paramref name="sync"/>, which returns whether it did; no request is sent meanwhile.
        /// If it did, a request waiting for another text is dropped, as the daemon would answer
        /// it for this one. Returns what <paramref name="sync"/> returns.
        /// </summary>
        public bool Sync(string text, Func<bool> sync)
        {
            lock (_lock)
            {
                if (!sync())
                    return false;
                _synced = text;
                if (_next != null && !string.Equals(_next.Text, text, StringComparison.Ordinal))
                    _next = null;
                return true;
            }
        }

        /// <summary>
        /// Forgets the last answer, so it isn't given again: the document is now on another
        /// daemon, which may answer differently.
        /// </summary>
        public void Forget()
        {
            lock (_lock)
            {
                _lastAnswered = null;
                _lastAnswer = null;
            }
        }

        private async Task Send(CaretRequest request)
        {
            while (request != null)
            {
                Task<T> asked = null;
                lock (_lock)
                {
                    if (string.Equals(request.Text, _synced, StringComparison.Ordinal))
                    {
                        try
                        {
                            asked = _ask(request.Line, request.Column);
                        }
                        catch (Exception exception)
                        {
                            asked = Task.FromException<T>(exception);
                        }
                    }
                }
                T answer = null;
                if (asked != null)
                {
                    try
                    {
                        answer = await asked.ConfigureAwait(false);
                    }
                    catch (Exception)
                    {
                        answer = null;
                    }
                }
                CaretRequest next;
                lock (_lock)
                {
                    if (answer != null)
                    {
                        _lastAnswered = request;
                        _lastAnswer = answer;
                    }
                    next = _next;
                    _next = null;
                    _inFlight = next;
                }
                if (asked != null)
                    _answered(request, answer ?? _none);
                request = next;
            }
        }
    }
}
