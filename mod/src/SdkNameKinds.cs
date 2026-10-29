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
    /// Tells the SDK's names apart for the Kotlin editor, from the name and, for a member, the
    /// name before its dot. Some names are several things (<c>Small</c> is a battery and a value of
    /// an enum; <c>Color</c> is an enum and a logic type), so the qualifier decides.
    /// </summary>
    public static class SdkNameKinds
    {
        /// <summary>The SDK's generated types and its hand-written ones.</summary>
        internal static readonly HashSet<string> Types = new HashSet<string>(
            SdkNames.Types.Concat(new[]
            {
                "Device", "Prefab", "TypedDevice", "GenericDevice", "Slot", "DeviceSlot", "Slots", "Batch",
                "DeviceBatch", "SlotBatch", "Mapped",
            }));

        /// <summary>Each enum's values, by the enum.</summary>
        internal static readonly Dictionary<string, HashSet<string>> Enums = SdkNames.Enums.ToDictionary(
            e => e.Key, e => new HashSet<string>(e.Value));

        internal static readonly HashSet<string> OperandEnums = new HashSet<string>(SdkNames.OperandEnums);

        /// <summary>The values of the enums but the operand enums, whose values are logic types.</summary>
        private static readonly HashSet<string> EnumValues = new HashSet<string>(
            Enums.Where(e => !OperandEnums.Contains(e.Key)).SelectMany(e => e.Value));

        internal static readonly HashSet<string> LogicTypes = new HashSet<string>(SdkNames.LogicTypes);
        private static readonly HashSet<string> Devices = new HashSet<string> { "d0", "d1", "d2", "d3", "d4", "d5", "db" };

        /// <summary>
        /// The SDK's functions. <c>Batch.all</c> isn't among them, as a family's <c>all</c> is a
        /// property and both follow a dot.
        /// </summary>
        internal static readonly HashSet<string> Functions = new HashSet<string>
        {
            "sleep", "yield", "hash", "str", "at", "named", "map", "sumOf", "minOf", "maxOf", "count", "any",
            "none", "forEach", "average", "sum", "min", "max", "toInt", "toDouble",
        };

        /// <summary>
        /// A name after a dot. After an enum, the name is that enum's value or nothing of the
        /// SDK's: a logic type after an operand enum (<c>LogicType.Pressure</c>), and an enum
        /// value after any other (<c>PowerMode.Idle</c>), as each enum's own values are looked up.
        /// After a family it is a nested prefab (<c>Battery.Small</c>). After anything else it is
        /// a logic type (<c>d0.Temperature</c>), a type or a function, but never an enum value, as
        /// only an enum has values. With no qualifier (a member starting a continuation line) any
        /// enum's value is possible too.
        /// </summary>
        public static NameKind Member(string qualifier, string name)
        {
            if (qualifier != null && Enums.TryGetValue(qualifier, out var values))
            {
                if (values.Contains(name))
                    return OperandEnums.Contains(qualifier) && LogicTypes.Contains(name)
                        ? NameKind.LogicType
                        : NameKind.EnumValue;
                return Functions.Contains(name) ? NameKind.Function : NameKind.None;
            }
            if (qualifier != null && Types.Contains(qualifier) && Types.Contains(name))
                return NameKind.Type;
            if (LogicTypes.Contains(name))
                return NameKind.LogicType;
            if (qualifier == null && EnumValues.Contains(name))
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
            if (Enums.ContainsKey(name))
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
