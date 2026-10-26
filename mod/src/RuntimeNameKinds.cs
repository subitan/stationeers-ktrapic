using System.Collections.Generic;
using System.Linq;

namespace KTrapIC
{
    /// <summary>What a name in a KTrapIC program is, as far as the editor colors it.</summary>
    public enum NameKind
    {
        None,
        Type,
        Enum,
        EnumValue,
        Device,
        LogicType,
        Function,
    }

    /// <summary>
    /// Tells the runtime's names apart for the Kotlin editor, from the name and, for a member, the
    /// name before its dot. Some names are several things (<c>Small</c> is a battery and a value of
    /// an enum; <c>Color</c> is an enum and a logic type), so the qualifier decides.
    /// </summary>
    public static class RuntimeNameKinds
    {
        /// <summary>The runtime's generated types and its hand-written ones.</summary>
        private static readonly HashSet<string> Types = new HashSet<string>(
            RuntimeNames.Types.Concat(new[]
            {
                "Device", "Prefab", "TypedDevice", "GenericDevice", "Slot", "DeviceSlot", "Slots", "Batch",
                "DeviceBatch", "SlotBatch", "Mapped",
            }));

        private static readonly HashSet<string> Enums = new HashSet<string>(RuntimeNames.Enums);
        private static readonly HashSet<string> EnumValues = new HashSet<string>(RuntimeNames.EnumValues);
        private static readonly HashSet<string> LogicTypes = new HashSet<string>(RuntimeNames.LogicTypes);
        private static readonly HashSet<string> Devices = new HashSet<string> { "d0", "d1", "d2", "d3", "d4", "d5", "db" };

        /// <summary>
        /// The runtime's functions. <c>Batch.all</c> isn't among them, as a family's <c>all</c> is a
        /// property and both follow a dot.
        /// </summary>
        private static readonly HashSet<string> Functions = new HashSet<string>
        {
            "sleep", "yield", "hash", "str", "at", "named", "map", "sumOf", "minOf", "maxOf", "count", "any",
            "none", "forEach", "average", "sum", "min", "max", "toInt", "toDouble",
        };

        /// <summary>
        /// A name after a dot: an enum's value after the enum (<c>Color.Red</c>), a nested prefab
        /// after its family (<c>Batteries.Small</c>), and otherwise a logic type
        /// (<c>d0.Temperature</c>), an enum value, a type or a function.
        /// </summary>
        public static NameKind Member(string qualifier, string name)
        {
            if (Enums.Contains(qualifier) && EnumValues.Contains(name))
                return NameKind.EnumValue;
            if (Types.Contains(qualifier) && Types.Contains(name))
                return NameKind.Type;
            if (LogicTypes.Contains(name))
                return NameKind.LogicType;
            if (EnumValues.Contains(name))
                return NameKind.EnumValue;
            if (Types.Contains(name))
                return NameKind.Type;
            if (Functions.Contains(name))
                return NameKind.Function;
            return NameKind.None;
        }

        /// <summary>A name not after a dot: an enum, a type, a device or a function.</summary>
        public static NameKind Name(string name)
        {
            if (Enums.Contains(name))
                return NameKind.Enum;
            if (Types.Contains(name))
                return NameKind.Type;
            if (Devices.Contains(name))
                return NameKind.Device;
            if (Functions.Contains(name))
                return NameKind.Function;
            return NameKind.None;
        }
    }
}
