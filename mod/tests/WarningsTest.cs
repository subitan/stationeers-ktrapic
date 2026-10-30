using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using KTrapIC;
using Newtonsoft.Json.Linq;
using Xunit;

/// <summary>
/// How the Kotlin editor shows the compiler's warnings, which the IC10 Editor drops: which are
/// marked in the code and which go to the status line, and the text of the hover, the status line
/// and the toolbar's count. The last tests take the warnings from a real daemon's compile.
/// </summary>
public class WarningsTest : IDisposable
{
    private TestConnection _daemon;

    public void Dispose() => _daemon?.Dispose();

    private static CompileResponse Warned(params (int Line, int Column, string Description)[] warnings) =>
        new CompileResponse
        {
            code = "s db Setting 1",
            warnings = warnings
                .Select(w => new CompileResponse.Problem { line = w.Line, column = w.Column, description = w.Description })
                .ToList(),
        };

    private static readonly Func<int, int, bool> Anywhere = (line, column) => true;

    [Fact]
    public void KeepsEveryWarningAndMarksThoseWithAPlaceThere()
    {
        var routed = Warnings.Route(
            Warned((2, 5, "Variable 'x' is never used"), (0, 0, "The program is 140 lines, over the chip's 128"), (1, 1, "Second")),
            Anywhere);

        Assert.Equal(3, routed.Count);
        // 1-based from the compiler, 0-based in the editor.
        Assert.Equal(new[] { (1, 4, "Variable 'x' is never used"), (0, 0, "Second") },
            routed.Placed.Select(w => (w.Line, w.Column, w.Message)));
        Assert.Equal(new[] { "The program is 140 lines, over the chip's 128" }, routed.Unplaced);
    }

    [Fact]
    public void AWarningAtLineZeroHasNoPlaceEvenWhereLineOneCouldBeMarked()
    {
        var asked = new List<(int, int)>();
        var routed = Warnings.Route(Warned((0, 0, "Too long")), (line, column) =>
        {
            asked.Add((line, column));
            return true;
        });

        Assert.Empty(routed.Placed);
        Assert.Equal(new[] { "Too long" }, routed.Unplaced);
        Assert.Empty(asked);
    }

    [Fact]
    public void AWarningWithNothingToMarkAtItsPlaceGoesToTheStatusLine()
    {
        var routed = Warnings.Route(Warned((3, 1, "Past the end"), (1, 2, "Here")), (line, column) => line < 2);

        Assert.Equal(new[] { "Here" }, routed.Placed.Select(w => w.Message));
        Assert.Equal(new[] { "Past the end" }, routed.Unplaced);
    }

    [Fact]
    public void AFailedCompileOrNoneHasNoWarnings()
    {
        var failed = Warned((1, 1, "Unused"));
        failed.error = new CompileResponse.Problem { line = 1, column = 1, description = "Unresolved reference" };

        Assert.Equal(0, Warnings.Route(failed, Anywhere).Count);
        Assert.Equal(0, Warnings.Route(null, Anywhere).Count);
        Assert.Equal(0, Warnings.Route(new CompileResponse { code = "" }, Anywhere).Count);
        Assert.Equal(0, Warnings.Route(CompileResponse.Failed("The compiler couldn't start"), Anywhere).Count);
    }

    [Fact]
    public void TheHoverSaysWarningAndWrapsTheMessage()
    {
        var lines = Warnings.HoverLines("Variable 'counter' is never used, so it could be removed", 30);

        Assert.Equal(new[] { "Warning: Variable 'counter' is", "never used, so it could be", "removed" }, lines);
        Assert.All(lines, line => Assert.True(line.Length <= 30, line));
    }

    [Fact]
    public void TheHoverKeepsAMessagesLines()
    {
        Assert.Equal(new[] { "Warning: first", "second" }, Warnings.HoverLines("first\nsecond", 72));
    }

    [Fact]
    public void AWordLongerThanTheWidthHasALineOfItsOwn()
    {
        Assert.Equal(new[] { "a", "abcdefghij", "b" }, Warnings.Wrap("a abcdefghij b", 5));
    }

    [Fact]
    public void TheStatusLineShowsTheWarningAtTheCaretFirst()
    {
        Assert.Equal(
            "Warning: Variable 'x' is never used",
            Warnings.StatusLine(new[] { "Variable 'x' is never used" }, new[] { "Too long" }));
    }

    [Fact]
    public void TheStatusLineShowsTheFirstWarningWithNoPlaceElsewhere()
    {
        Assert.Equal("Warning: Too long: compile it compact", Warnings.StatusLine(null, new[] { "Too long:\ncompile it compact" }));
        Assert.Equal("Warning: Too long (and 1 more)", Warnings.StatusLine(new string[0], new[] { "Too long", "Tail calls" }));
        Assert.Null(Warnings.StatusLine(null, new string[0]));
        Assert.Null(Warnings.StatusLine(new string[0], null));
    }

    [Fact]
    public void TheToolbarCountsTheWarnings()
    {
        Assert.Null(Warnings.CountText(0));
        Assert.Equal("1 warning", Warnings.CountText(1));
        Assert.Equal("3 warnings", Warnings.CountText(3));
    }

    [Fact]
    public void TheToolbarCountListsThoseWithNoPlaceFirstThenTheOthersByPlace()
    {
        var routed = Warnings.Route(Warned((5, 2, "Later"), (0, 0, "Too long"), (2, 7, "Earlier")), Anywhere);

        Assert.Equal(
            new[] { "Warning: Too long", "Warning: line 2, column 7: Earlier", "Warning: line 5, column 2: Later" },
            Warnings.SummaryLines(routed, 72));
    }

    private async Task<CompileResponse> Compile(string text, CompileOptions options)
    {
        _daemon = new TestConnection();
        var document = new LspDocument(LspDocument.NewUri("chip"));
        document.Bind(_daemon.Connection);
        Assert.True(document.Sync(1, () => text, new JArray()));
        var response = await TestDaemon.Within(document.Compile(options));
        _daemon.AssertCompiled(response);
        return response;
    }

    [Fact]
    public async Task AKotlinWarningIsMarkedWhereTheCompilerPlacesIt()
    {
        // A call to a deprecated function: Kotlin warns of it at the call.
        var response = await Compile("@Deprecated(\"old\") fun f() = 1.0\ndb.Setting = f()\n", new CompileOptions(false));

        var routed = Warnings.Route(response, Anywhere);

        var warning = Assert.Single(routed.Placed);
        Assert.Empty(routed.Unplaced);
        Assert.Equal((1, 13), (warning.Line, warning.Column));
        Assert.Contains("deprecated", warning.Message);
        Assert.Equal("Warning: " + warning.Message, Assert.Single(Warnings.HoverLines(warning.Message, Hovers.Width)));
    }

    [Fact]
    public async Task AProgramOverTheChipsLinesIsWarnedAboutInTheStatusLine()
    {
        string text = string.Concat(Enumerable.Range(1, 140).Select(i => $"db.Setting = {i}.0\n"));
        var response = await Compile(text, new CompileOptions(false));

        var routed = Warnings.Route(response, Anywhere);

        Assert.Empty(routed.Placed);
        string warning = Assert.Single(routed.Unplaced);
        Assert.StartsWith("The program is 140 lines, over the chip's 128", warning);
        Assert.StartsWith("Warning: The program is 140 lines", Warnings.StatusLine(null, routed.Unplaced));
        Assert.Equal("1 warning", Warnings.CountText(routed.Count));
    }
}
