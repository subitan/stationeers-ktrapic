using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using KTrapIC;
using Xunit;

/// <summary>Which of the daemon's completions the Kotlin editor shows, and what accepting inserts.</summary>
public class CompletionsTest
{
    private static Completion Item(string label, int order) =>
        new Completion { label = label, kind = 10, sortText = order.ToString("D5") };

    [Theory]
    [InlineData("d0.Sett", 7, "Sett")]
    [InlineData("d0.", 3, "")]
    [InlineData("val x_1", 7, "x_1")]
    [InlineData("foo(bar", 3, "foo")]
    [InlineData("foo(bar", 4, "")]
    public void WordBeforeIsTheIdentifierEndingAtTheCaret(string line, int column, string word) =>
        Assert.Equal(word, Completions.WordBefore(line, column));

    [Theory]
    [InlineData("d0.", 3, true)]
    [InlineData("d0.Se", 5, true)]
    [InlineData("val x = ", 8, false)]
    [InlineData("x = 12", 6, false)]
    [InlineData("", 0, false)]
    [InlineData("val x = 1.", 10, false)]
    public void WantedAfterAWordOrADot(string line, int column, bool wanted) =>
        Assert.Equal(wanted, Completions.Wanted(line, column));

    [Theory]
    [InlineData("val e = Leds..at(d0)", 13, true)]
    [InlineData("f().", 4, true)]
    [InlineData("d0[LogicType.On]", 13, true)]
    [InlineData("for (i in 0..3)", 12, false)]
    [InlineData("for (i in 0..3)", 13, false)]
    [InlineData("x = 1.5", 6, false)]
    [InlineData("d0.Se", 5, false)]
    [InlineData(".", 1, false)]
    public void AfterDotFollowingANameOrABracket(string line, int column, bool after) =>
        Assert.Equal(after, Completions.AfterDot(line, column));

    [Fact]
    public void ShowsThoseStartingWithTheWordInTheDaemonsOrder()
    {
        var choice = Completions.Choose(
            new[] { Item("Setting", 2), Item("Open", 0), Item("SettingInput", 1), Item("setting", 3) },
            "Set");

        Assert.Equal(new[] { "SettingInput", "Setting" }, choice.Shown.Select(c => c.label));
        Assert.Equal(2, choice.Matched);
        Assert.Equal("ting", choice.Insert);
    }

    [Fact]
    public void ShowsAtMostTwentyButCountsAll()
    {
        var choice = Completions.Choose(Enumerable.Range(0, 30).Select(i => Item("Name" + i, i)), "");

        Assert.Equal(Completions.MaxShown, choice.Shown.Count);
        Assert.Equal(30, choice.Matched);
        Assert.Equal("Name", choice.Insert);
    }

    [Fact]
    public void NothingToInsertWhenTheyShareNoMoreThanTheWord()
    {
        var choice = Completions.Choose(new[] { Item("On", 0), Item("Open", 1) }, "O");

        Assert.Null(choice.Insert);
    }

    /// <summary>
    /// A daemon with a copy of the document's text, whose completion requests are answered when
    /// the test says.
    /// </summary>
    private sealed class FakeDaemon
    {
        /// <summary>The document's text here, as the editor last synced it.</summary>
        public string Text;

        /// <summary>Each request sent: its caret, the text it was sent in, and its answer to come.</summary>
        public readonly List<(int Line, int Column, string Text, TaskCompletionSource<IReadOnlyList<Completion>> Answer)> Asked =
            new List<(int, int, string, TaskCompletionSource<IReadOnlyList<Completion>>)>();

        public readonly List<(CaretRequest Request, IReadOnlyList<Completion> Completions)> Answered =
            new List<(CaretRequest, IReadOnlyList<Completion>)>();

        public readonly CompletionRequests Requests;

        public FakeDaemon() =>
            Requests = new CompletionRequests(
                (line, column) =>
                {
                    var answer = new TaskCompletionSource<IReadOnlyList<Completion>>();
                    Asked.Add((line, column, Text, answer));
                    return answer.Task;
                },
                (request, completions) => Answered.Add((request, completions)));

        /// <summary>An edit, which the editor syncs whether or not it wants completions after it.</summary>
        public void Edit(string text) =>
            Requests.Sync(text, () =>
            {
                Text = text;
                return true;
            });

        /// <summary>Completions wanted, as the editor wants them: with the text they're in synced first.</summary>
        public void Want(CaretRequest request)
        {
            Edit(request.Text);
            Requests.Want(request);
        }

        public void Answer(int index, params Completion[] completions) =>
            Inline(() => Asked[index].Answer.SetResult(completions));

        /// <summary>Answers with one completion whose detail is the text the request was sent in.</summary>
        public void AnswerFromText(int index) =>
            Answer(index, new Completion { label = "x" + index, kind = 10, detail = Asked[index].Text });

        public void Fail(int index, Exception exception) =>
            Inline(() => Asked[index].Answer.SetException(exception));

        /// <summary>
        /// Runs <paramref name="complete"/> with no synchronization context, so the requests'
        /// continuations run within it rather than being queued, and are done when it returns.
        /// </summary>
        private static void Inline(Action complete)
        {
            var context = SynchronizationContext.Current;
            SynchronizationContext.SetSynchronizationContext(null);
            try
            {
                complete();
            }
            finally
            {
                SynchronizationContext.SetSynchronizationContext(context);
            }
        }
    }

    /// <summary>
    /// The code after typing <paramref name="typed"/> on a line of its own, as the editor gives it:
    /// a new string each time, as a keystroke's two calls each read the code again.
    /// </summary>
    private static string Code(string typed) => string.Concat("val x = 1\n", typed);

    [Fact]
    public void OneRequestInFlightAndOnlyTheLatestWantedFollowsIt()
    {
        var daemon = new FakeDaemon();

        // A burst of keystrokes while the first request is in flight.
        for (int column = 1; column <= 20; column++)
            daemon.Want(new CaretRequest(Code(new string('x', column)), 1, column, "x"));
        Assert.Single(daemon.Asked);
        daemon.Answer(0, Item("x1", 0));

        Assert.Equal(new[] { 1, 20 }, daemon.Asked.Select(a => a.Column));
        daemon.Answer(1, Item("x20", 0));
        Assert.Equal(new[] { 1, 20 }, daemon.Answered.Select(a => a.Request.Column));
        Assert.Equal("x20", daemon.Answered[1].Completions.Single().label);
    }

    [Fact]
    public void AKeystrokesTwoAsksAreSentOnce()
    {
        var daemon = new FakeDaemon();

        // The editor asks once before the formatter counts the edit and once after, at the same
        // caret in the same text.
        daemon.Want(new CaretRequest(Code("d0."), 1, 3, ""));
        daemon.Want(new CaretRequest(Code("d0."), 1, 3, ""));
        daemon.Answer(0, Item("On", 0));
        // The caret moves away and back.
        daemon.Want(new CaretRequest(Code("d0."), 1, 3, ""));

        Assert.Single(daemon.Asked);
        Assert.Equal(2, daemon.Answered.Count);
        Assert.All(daemon.Answered, a => Assert.Equal("On", a.Completions.Single().label));
    }

    [Fact]
    public void AnEditThatKeepsTheCaretIsAskedAgain()
    {
        var daemon = new FakeDaemon();

        daemon.Want(new CaretRequest(Code("d0.x"), 1, 3, ""));
        daemon.Answer(0, Item("On", 0));
        // Delete, to the right of the caret.
        daemon.Want(new CaretRequest(Code("d0."), 1, 3, ""));

        Assert.Equal(2, daemon.Asked.Count);
        Assert.Single(daemon.Answered);
    }

    [Fact]
    public void ARequestWantedAgainWhileInFlightDropsTheOneWaiting()
    {
        var daemon = new FakeDaemon();

        daemon.Want(new CaretRequest(Code("d0."), 1, 3, ""));
        daemon.Want(new CaretRequest(Code("d0.S"), 1, 4, "S"));
        daemon.Want(new CaretRequest(Code("d0."), 1, 3, ""));
        daemon.Answer(0);

        Assert.Single(daemon.Asked);
    }

    [Fact]
    public void AFailedRequestIsAnsweredWithNoneAndTheNextIsSent()
    {
        var daemon = new FakeDaemon();

        daemon.Want(new CaretRequest(Code("d0."), 1, 3, ""));
        daemon.Want(new CaretRequest(Code("d0.S"), 1, 4, "S"));
        daemon.Fail(0, new InvalidOperationException("stopped"));

        Assert.Empty(daemon.Answered.Single().Completions);
        Assert.Equal(2, daemon.Asked.Count);
    }

    [Fact]
    public void ARequestWithNoAnswerIsAskedAgain()
    {
        var daemon = new FakeDaemon();

        daemon.Want(new CaretRequest(Code("d0."), 1, 3, ""));
        daemon.Answer(0, null);
        daemon.Want(new CaretRequest(Code("d0."), 1, 3, ""));
        daemon.Fail(1, new InvalidOperationException("stopped"));
        daemon.Want(new CaretRequest(Code("d0."), 1, 3, ""));

        Assert.Equal(3, daemon.Asked.Count);
        Assert.All(daemon.Answered, a => Assert.Empty(a.Completions));
    }

    [Fact]
    public void AForgottenAnswerIsAskedAgain()
    {
        var daemon = new FakeDaemon();

        daemon.Want(new CaretRequest(Code("d0."), 1, 3, ""));
        daemon.Answer(0, Item("On", 0));
        daemon.Requests.Forget();
        daemon.Want(new CaretRequest(Code("d0."), 1, 3, ""));

        Assert.Equal(2, daemon.Asked.Count);
    }

    [Fact]
    public void ARequestWaitingIsDroppedByAnEditAndAnswersAreOfTheirOwnText()
    {
        var daemon = new FakeDaemon();

        daemon.Want(new CaretRequest(Code("x"), 1, 1, "x"));
        // Typed while the first is in flight: waits.
        daemon.Want(new CaretRequest(Code("x."), 1, 2, ""));
        // Home, Enter: an edit that wants no completions.
        daemon.Edit(Code("\nx."));
        daemon.AnswerFromText(0);

        Assert.Single(daemon.Asked);

        // Undone: wanted again in its own text, it is sent, and its answer is given again there.
        daemon.Want(new CaretRequest(Code("x."), 1, 2, ""));
        daemon.AnswerFromText(1);
        daemon.Want(new CaretRequest(Code("x."), 1, 2, ""));

        Assert.Equal(2, daemon.Asked.Count);
        Assert.Equal(3, daemon.Answered.Count);
        Assert.All(daemon.Answered, a => Assert.Equal(a.Request.Text, a.Completions.Single().detail));
    }

    [Fact]
    public void ARequestInATextNotSyncedIsNeitherSentNorAnswered()
    {
        var daemon = new FakeDaemon();

        daemon.Edit(Code("d0"));
        daemon.Requests.Want(new CaretRequest(Code("d0."), 1, 3, ""));

        Assert.Empty(daemon.Asked);
        Assert.Empty(daemon.Answered);
    }

    [Fact]
    public void ASyncNotSentKeepsTheRequestWaiting()
    {
        var daemon = new FakeDaemon();

        daemon.Want(new CaretRequest(Code("d0."), 1, 3, ""));
        daemon.Want(new CaretRequest(Code("d0.S"), 1, 4, "S"));
        // The daemon isn't initialized: the edit isn't sent, so the daemon's text is still the waiting request's.
        Assert.False(daemon.Requests.Sync(Code("d0.Se"), () => false));
        daemon.Answer(0);

        Assert.Equal(new[] { 3, 4 }, daemon.Asked.Select(a => a.Column));
    }

    [Fact]
    public void NothingShownForNoMatchOrOnlyTheWordItself()
    {
        Assert.Null(Completions.Choose(new[] { Item("On", 0) }, "x"));
        Assert.Null(Completions.Choose(new[] { Item("On", 0) }, "On"));
        Assert.Null(Completions.Choose(new[] { Item("_hidden", 0) }, ""));
    }
}
