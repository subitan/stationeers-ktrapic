using System.Collections.Generic;
using System.Linq;
using KTrapIC;
using Xunit;

public class RuntimeNameKindsTest
{
    [Theory]
    // Small, Medium and Large are batteries and values of an enum; the qualifier decides.
    [InlineData("Batteries", "Small", NameKind.Type)]
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
    [InlineData("Batteries", "None", NameKind.None)]
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
        Assert.Equal(kind, RuntimeNameKinds.Member(qualifier, name));
    }

    [Theory]
    [InlineData("Color", NameKind.Enum)]
    [InlineData("Batteries", NameKind.Type)]
    [InlineData("DeviceBatch", NameKind.Type)]
    [InlineData("db", NameKind.Device)]
    [InlineData("sleep", NameKind.Function)]
    [InlineData("unknown", NameKind.None)]
    public void Name(string name, NameKind kind)
    {
        Assert.Equal(kind, RuntimeNameKinds.Name(name));
    }

    /// <summary>
    /// The sets of names the editor colors: the types, each enum's values, the logic types, the
    /// functions and the enums themselves.
    /// </summary>
    private static IEnumerable<IEnumerable<string>> NameSets()
    {
        yield return RuntimeNameKinds.Types;
        foreach (var values in RuntimeNameKinds.Enums.Values)
            yield return values;
        yield return RuntimeNameKinds.LogicTypes;
        yield return RuntimeNameKinds.Functions;
        yield return RuntimeNameKinds.Enums.Keys;
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
        RuntimeNameKinds.Types.Concat(new[] { "d0", "d1", "d2", "d3", "d4", "d5", "db", "x" });

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
        foreach (var e in RuntimeNameKinds.Enums)
        {
            foreach (var name in Overlaps)
            {
                var kind = RuntimeNameKinds.Member(e.Key, name);
                NameKind? expected = null;
                if (e.Value.Contains(name))
                    expected = RuntimeNameKinds.OperandEnums.Contains(e.Key) && RuntimeNameKinds.LogicTypes.Contains(name)
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
                if (RuntimeNameKinds.Member(qualifier, name) == NameKind.EnumValue)
                    failures.Add($"{qualifier}.{name}");
            }
        }
        Assert.Empty(failures);
    }

    [Fact]
    public void AfterAFamilyATypeIsAType()
    {
        var failures = new List<string>();
        foreach (var qualifier in RuntimeNameKinds.Types)
        {
            foreach (var name in Overlaps.Where(RuntimeNameKinds.Types.Contains))
            {
                var kind = RuntimeNameKinds.Member(qualifier, name);
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
            foreach (var name in Overlaps.Where(RuntimeNameKinds.LogicTypes.Contains))
            {
                var kind = RuntimeNameKinds.Member(qualifier, name);
                if (kind != NameKind.LogicType)
                    failures.Add($"{qualifier ?? "(none)"}.{name}: {kind}");
            }
        }
        Assert.Empty(failures);
    }

    [Fact]
    public void WithNoQualifierAnEnumValueThatIsNoLogicTypeIsAValue()
    {
        var values = RuntimeNameKinds.Enums
            .Where(e => !RuntimeNameKinds.OperandEnums.Contains(e.Key))
            .SelectMany(e => e.Value);
        var failures = Overlaps
            .Where(name => values.Contains(name) && !RuntimeNameKinds.LogicTypes.Contains(name))
            .Where(name => RuntimeNameKinds.Member(null, name) != NameKind.EnumValue)
            .ToList();
        Assert.Empty(failures);
    }
}
