using KTrapIC;
using Newtonsoft.Json.Linq;
using Xunit;

/// <summary>Where the Kotlin editor asks for hovers and signatures, and how it reads the answers.</summary>
public class HoversTest
{
    [Theory]
    [InlineData("d0.Setting = 1.0", 3, 3)]
    [InlineData("d0.Setting = 1.0", 9, 3)]
    [InlineData("d0.Setting = 1.0", 0, 0)]
    [InlineData("val x_1 = 2", 6, 4)]
    public void TheStartOfTheNameUnderTheMouse(string line, int column, int start)
    {
        Assert.Equal(start, Hovers.NameStart(line, column));
    }

    [Theory]
    [InlineData("d0.Setting = 1.0", 2)] // the dot
    [InlineData("d0.Setting = 1.0", 10)] // a space
    [InlineData("d0.Setting = 1.0", 13)] // a number
    [InlineData("d0.Setting = 1.0", 16)] // past the end
    [InlineData("d0.Setting = 1.0", -1)]
    [InlineData("", 0)]
    public void NoNameElsewhere(string line, int column)
    {
        Assert.Null(Hovers.NameStart(line, column));
    }

    [Theory]
    [InlineData("sleep(", 6, true)]
    [InlineData("mix(1.0, ", 9, true)]
    [InlineData("mix(1.0,", 8, true)]
    [InlineData("f(  ", 4, true)]
    [InlineData("f(x", 3, false)]
    [InlineData("f()", 3, false)]
    [InlineData("d0.", 3, false)]
    [InlineData("   ", 3, false)]
    [InlineData("", 0, false)]
    public void SignaturesWhereAnArgumentIsAboutToBeTyped(string line, int column, bool wanted)
    {
        Assert.Equal(wanted, Hovers.InArguments(line, column));
    }

    [Fact]
    public void ReadsAnLspSignatureHelp()
    {
        var help = JObject.Parse(@"{
            ""signatures"": [
                {""label"": ""fun f(a: Int, b: Int): Int"",
                 ""documentation"": {""kind"": ""plaintext"", ""value"": ""Adds.""},
                 ""parameters"": [{""label"": ""a: Int""}, {""label"": ""b: Int""}]},
                {""label"": ""fun f(): Int""}
            ],
            ""activeSignature"": 0,
            ""activeParameter"": 1
        }");

        var signatures = Signatures.FromLsp(help);

        Assert.Equal(2, signatures.All.Count);
        Assert.Equal("fun f(a: Int, b: Int): Int", signatures.All[0].Label);
        Assert.Equal(new[] { "a: Int", "b: Int" }, signatures.All[0].Parameters);
        Assert.Equal("Adds.", signatures.All[0].Documentation);
        Assert.Empty(signatures.All[1].Parameters);
        Assert.Null(signatures.All[1].Documentation);
        Assert.Equal(1, signatures.ActiveParameter);
    }

    [Fact]
    public void ANullSignatureHelpHasNone()
    {
        Assert.Empty(Signatures.FromLsp(null).All);
    }
}
