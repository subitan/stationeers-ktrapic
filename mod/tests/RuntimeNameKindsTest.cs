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
    [InlineData("LogicType", "Temperature", NameKind.LogicType)]
    // Color is an enum and a logic type.
    [InlineData("db", "Color", NameKind.LogicType)]
    [InlineData("d0", "Temperature", NameKind.LogicType)]
    [InlineData("Small", "named", NameKind.Function)]
    [InlineData("x", "Unknown", NameKind.None)]
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
}
