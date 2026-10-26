using BepInEx.Logging;

namespace KTrapIC
{
    /// <summary>The plugin's log, BepInEx's for this plugin once <see cref="Use"/> gives it.</summary>
    internal static class Log
    {
        private static ManualLogSource _source;

        public static void Use(ManualLogSource source) => _source = source;

        public static void Debug(string message) => _source?.LogDebug(message);

        public static void Info(string message) => _source?.LogInfo(message);

        public static void Warning(string message) => _source?.LogWarning(message);

        public static void Error(string message) => _source?.LogError(message);
    }
}
