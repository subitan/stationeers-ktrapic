using System.Collections.Generic;
using System.Linq;
using KTrapIC;
using Xunit;

public class SdkNameKindsTest
{
    [Theory]
    // Small, Medium and Large are batteries and values of an enum; the qualifier decides.
    [InlineData("LedDisplay", "Small", NameKind.Type)]
    [InlineData("Battery", "Large", NameKind.Type)]
    [InlineData("Color", "Red", NameKind.EnumValue)]
    // Idle, Fuel and Maximum are values of an enum and logic types; after an enum, they're values.
    [InlineData("PowerMode", "Idle", NameKind.EnumValue)]
    [InlineData("GasType", "Fuel", NameKind.EnumValue)]
    [InlineData("LogicBatchMethod", "Maximum", NameKind.EnumValue)]
    [InlineData("RobotMode", "None", NameKind.EnumValue)]
    // After an operand enum, its values are logic types, even those other enums have too.
    [InlineData("LogicType", "Temperature", NameKind.LogicType)]
    [InlineData("LogicType", "Pressure", NameKind.LogicType)]
    [InlineData("LogicType", "Idle", NameKind.LogicType)]
    [InlineData("LogicSlotType", "Pressure", NameKind.LogicType)]
    [InlineData("LogicType", "None", NameKind.EnumValue)]
    // An enum has only its own values, and nothing else has values.
    [InlineData("PowerMode", "Red", NameKind.None)]
    [InlineData("Battery", "None", NameKind.None)]
    [InlineData("x", "None", NameKind.None)]
    // Color is an enum and a logic type.
    [InlineData("db", "Color", NameKind.LogicType)]
    [InlineData("d0", "Temperature", NameKind.LogicType)]
    [InlineData("Small", "named", NameKind.Function)]
    [InlineData("x", "Unknown", NameKind.None)]
    // A member starting a continuation line has no qualifier and is colored by its name alone.
    [InlineData(null, "Red", NameKind.EnumValue)]
    [InlineData(null, "Pressure", NameKind.LogicType)]
    public void Member(string qualifier, string name, NameKind kind)
    {
        Assert.Equal(kind, SdkNameKinds.Member(qualifier, name));
    }

    [Theory]
    [InlineData("Color", NameKind.Enum)]
    [InlineData("Battery", NameKind.Type)]
    [InlineData("DeviceBatch", NameKind.Type)]
    [InlineData("db", NameKind.Device)]
    [InlineData("sleep", NameKind.Function)]
    [InlineData("unknown", NameKind.None)]
    public void Name(string name, NameKind kind)
    {
        Assert.Equal(kind, SdkNameKinds.Name(name));
    }

    /// <summary>
    /// The sets of names the editor colors: the types, each enum's values, the logic types, the
    /// functions and the enums themselves.
    /// </summary>
    private static IEnumerable<IEnumerable<string>> NameSets()
    {
        yield return SdkNameKinds.Types;
        foreach (var values in SdkNameKinds.Enums.Values)
            yield return values;
        yield return SdkNameKinds.LogicTypes;
        yield return SdkNameKinds.Functions;
        yield return SdkNameKinds.Enums.Keys;
    }

    /// <summary>Every name in more than one of <see cref="NameSets"/>.</summary>
    private static readonly HashSet<string> Overlaps = new HashSet<string>(
        NameSets()
            .SelectMany(set => set.Distinct())
            .GroupBy(name => name)
            .Where(group => group.Count() > 1)
            .Select(group => group.Key));

    /// <summary>The qualifiers that aren't enums: every type, each device and a variable.</summary>
    private static IEnumerable<string> NonEnumQualifiers() =>
        SdkNameKinds.Types.Concat(new[] { "d0", "d1", "d2", "d3", "d4", "d5", "db", "x" });

    [Fact]
    public void OverlapsIncludeTheKnownOnes()
    {
        Assert.Superset(
            new HashSet<string> { "Small", "Color", "Idle", "Pressure", "Maximum", "None" },
            Overlaps);
    }

    [Fact]
    public void AfterAnEnumOnlyItsOwnValuesAreValues()
    {
        var failures = new List<string>();
        foreach (var e in SdkNameKinds.Enums)
        {
            foreach (var name in Overlaps)
            {
                var kind = SdkNameKinds.Member(e.Key, name);
                NameKind? expected = null;
                if (e.Value.Contains(name))
                    expected = SdkNameKinds.OperandEnums.Contains(e.Key) && SdkNameKinds.LogicTypes.Contains(name)
                        ? NameKind.LogicType
                        : NameKind.EnumValue;
                else if (kind == NameKind.EnumValue || kind == NameKind.LogicType)
                    expected = NameKind.None;
                if (expected.HasValue && kind != expected.Value)
                    failures.Add($"{e.Key}.{name}: {kind}, expected {expected.Value}");
            }
        }
        Assert.Empty(failures);
    }

    [Fact]
    public void AfterAnythingButAnEnumNothingIsAValue()
    {
        var failures = new List<string>();
        foreach (var qualifier in NonEnumQualifiers())
        {
            foreach (var name in Overlaps)
            {
                if (SdkNameKinds.Member(qualifier, name) == NameKind.EnumValue)
                    failures.Add($"{qualifier}.{name}");
            }
        }
        Assert.Empty(failures);
    }

    [Fact]
    public void AfterAFamilyATypeIsAType()
    {
        var failures = new List<string>();
        foreach (var qualifier in SdkNameKinds.Types)
        {
            foreach (var name in Overlaps.Where(SdkNameKinds.Types.Contains))
            {
                var kind = SdkNameKinds.Member(qualifier, name);
                if (kind != NameKind.Type)
                    failures.Add($"{qualifier}.{name}: {kind}");
            }
        }
        Assert.Empty(failures);
    }

    [Fact]
    public void AfterADeviceOrVariableALogicTypeIsALogicType()
    {
        var failures = new List<string>();
        foreach (var qualifier in new[] { "d0", "d1", "d2", "d3", "d4", "d5", "db", "x", ")", null })
        {
            foreach (var name in Overlaps.Where(SdkNameKinds.LogicTypes.Contains))
            {
                var kind = SdkNameKinds.Member(qualifier, name);
                if (kind != NameKind.LogicType)
                    failures.Add($"{qualifier ?? "(none)"}.{name}: {kind}");
            }
        }
        Assert.Empty(failures);
    }

    [Fact]
    public void WithNoQualifierAnEnumValueThatIsNoLogicTypeIsAValue()
    {
        var values = SdkNameKinds.Enums
            .Where(e => !SdkNameKinds.OperandEnums.Contains(e.Key))
            .SelectMany(e => e.Value);
        var failures = Overlaps
            .Where(name => values.Contains(name) && !SdkNameKinds.LogicTypes.Contains(name))
            .Where(name => SdkNameKinds.Member(null, name) != NameKind.EnumValue)
            .ToList();
        Assert.Empty(failures);
    }
}
