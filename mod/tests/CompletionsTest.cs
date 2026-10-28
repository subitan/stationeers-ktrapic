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
    public void WantedAfterAWordOrADot(string line, int column, bool wanted) =>
        Assert.Equal(wanted, Completions.Wanted(line, column));

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

    /// <summary>A daemon whose completion requests are answered when the test says.</summary>
    private sealed class FakeDaemon
    {
        public readonly List<(int Line, int Column, TaskCompletionSource<IReadOnlyList<Completion>> Answer)> Asked =
            new List<(int, int, TaskCompletionSource<IReadOnlyList<Completion>>)>();

        public readonly List<(CompletionRequest Request, IReadOnlyList<Completion> Completions)> Answered =
            new List<(CompletionRequest, IReadOnlyList<Completion>)>();

        public readonly CompletionRequests Requests;

        public FakeDaemon() =>
            Requests = new CompletionRequests(
                (line, column) =>
                {
                    var answer = new TaskCompletionSource<IReadOnlyList<Completion>>();
                    Asked.Add((line, column, answer));
                    return answer.Task;
                },
                (request, completions) => Answered.Add((request, completions)));

        public void Answer(int index, params Completion[] completions) =>
            Inline(() => Asked[index].Answer.SetResult(completions));

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

    [Fact]
    public void OneRequestInFlightAndOnlyTheLatestWantedFollowsIt()
    {
        var daemon = new FakeDaemon();

        // A burst of keystrokes while the first request is in flight.
        for (int column = 1; column <= 20; column++)
            daemon.Requests.Want(new CompletionRequest(column, 0, column, "x"));
        Assert.Single(daemon.Asked);
        daemon.Answer(0, Item("x1", 0));

        Assert.Equal(new[] { 1, 20 }, daemon.Asked.Select(a => a.Column));
        daemon.Answer(1, Item("x20", 0));
        Assert.Equal(new[] { 1, 20 }, daemon.Answered.Select(a => a.Request.Version));
        Assert.Equal("x20", daemon.Answered[1].Completions.Single().label);
    }

    [Fact]
    public void TheSameCaretInTheSameVersionIsAskedOnce()
    {
        var daemon = new FakeDaemon();

        daemon.Requests.Want(new CompletionRequest(1, 0, 3, ""));
        daemon.Requests.Want(new CompletionRequest(1, 0, 3, ""));
        daemon.Answer(0, Item("On", 0));
        daemon.Requests.Want(new CompletionRequest(1, 0, 3, ""));

        Assert.Single(daemon.Asked);
        Assert.Equal(2, daemon.Answered.Count);
        Assert.All(daemon.Answered, a => Assert.Equal("On", a.Completions.Single().label));
    }

    [Fact]
    public void ARequestWantedAgainWhileInFlightDropsTheOneWaiting()
    {
        var daemon = new FakeDaemon();

        daemon.Requests.Want(new CompletionRequest(1, 0, 3, ""));
        daemon.Requests.Want(new CompletionRequest(2, 0, 4, "S"));
        daemon.Requests.Want(new CompletionRequest(1, 0, 3, ""));
        daemon.Answer(0);

        Assert.Single(daemon.Asked);
    }

    [Fact]
    public void AFailedRequestIsAnsweredWithNoneAndTheNextIsSent()
    {
        var daemon = new FakeDaemon();

        daemon.Requests.Want(new CompletionRequest(1, 0, 3, ""));
        daemon.Requests.Want(new CompletionRequest(2, 0, 4, "S"));
        daemon.Fail(0, new InvalidOperationException("stopped"));

        Assert.Empty(daemon.Answered.Single().Completions);
        Assert.Equal(2, daemon.Asked.Count);
    }

    [Fact]
    public void NothingShownForNoMatchOrOnlyTheWordItself()
    {
        Assert.Null(Completions.Choose(new[] { Item("On", 0) }, "x"));
        Assert.Null(Completions.Choose(new[] { Item("On", 0) }, "On"));
        Assert.Null(Completions.Choose(new[] { Item("_hidden", 0) }, ""));
    }
}
