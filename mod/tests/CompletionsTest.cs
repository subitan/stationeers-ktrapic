using System.Linq;
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

    [Fact]
    public void NothingShownForNoMatchOrOnlyTheWordItself()
    {
        Assert.Null(Completions.Choose(new[] { Item("On", 0) }, "x"));
        Assert.Null(Completions.Choose(new[] { Item("On", 0) }, "On"));
        Assert.Null(Completions.Choose(new[] { Item("_hidden", 0) }, ""));
    }
}
