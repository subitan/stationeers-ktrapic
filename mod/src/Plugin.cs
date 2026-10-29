using System;
using BepInEx;
using BepInEx.Configuration;
using Cysharp.Threading.Tasks;
using StationeersIC10Editor;
using Util.Commands;

namespace KTrapIC
{
    /// <summary>
    /// The KTrapIC plugin: adds the "Kotlin" language to the IC10 Editor, so chips can be
    /// programmed in KTrapIC's Kotlin and exported as IC10, and the <c>ktrapic</c> console command.
    /// </summary>
    [BepInDependency("com.github.aproposmath.StationeersIC10Editor", BepInDependency.DependencyFlags.HardDependency)]
    [BepInPlugin(ModInfo.Id, "KTrapIC", ModInfo.Version)]
    public class Plugin : BaseUnityPlugin
    {
        public static ConfigEntry<bool> Compact;
        public static ConfigEntry<bool> StackCalls;
        public static ConfigEntry<bool> TailCalls;

        /// <summary>The options each compile is sent with, as the settings are now.</summary>
        public static CompileOptions CompileOptions => 
            new CompileOptions(Compact.Value, StackCalls.Value, TailCalls.Value);
        public static ConfigEntry<string> JavaPath;
        public static ConfigEntry<string> DaemonJarPath;

        private void Awake()
        {
            try
            {
                Log.Use(Logger);
                Compact = Config.Bind(
                    "General",
                    "Compact",
                    false,
                    "Resolve labels to line numbers and drop them, to save lines on the chip");
                StackCalls = Config.Bind(
                    "General",
                    "StackCalls",
                    true,
                    "Pass functions' arguments and results on the chip's stack, so functions may be recursive; off passes them in registers");
                TailCalls = Config.Bind(
                    "General",
                    "TailCalls",
                    true,
                    "Compile a function's call to itself in tail position as a jump back to its start, so it takes no stack; has no effect without stack calls");
                JavaPath = Config.Bind(
                    "Development",
                    "JavaPath",
                    "",
                    "A java executable (21 or later) to run the compiler with, instead of the downloaded runtime");
                DaemonJarPath = Config.Bind(
                    "Development",
                    "DaemonJarPath",
                    "",
                    "A KTrapIC daemon jar (ktrapic-daemon_deploy.jar) to compile with, instead of the downloaded one");
                CodeFormatters.RegisterFormatter("Kotlin", typeof(KotlinFormatter));
                CommandLine._commandsMap["ktrapic"] = new KTrapICCommand();
                Log.Info($"KTrapIC {ModInfo.Version} loaded");
            }
            catch (Exception e)
            {
                Log.Error($"KTrapIC failed to load: {e}");
            }
        }

        private void OnDestroy() => Daemon.Stop();
    }

    internal class KTrapICCommand : CommandBase
    {
        public override string HelpText =>
            @"Usage: ktrapic <command>

Commands:
    status     Whether the KTrapIC compiler is running
    restart    Restart the compiler
    reinstall  Download the compiler and its Java runtime again, then restart it
    version    The plugin's version and the compiler release it uses";

        public override string[] Arguments { get; } = { "status|restart|reinstall|version" };

        public override bool IsLaunchCmd => false;

        public override string Execute(string[] args)
        {
            switch (args.Length == 1 ? args[0].ToLowerInvariant() : "")
            {
                case "status":
                    if (Runtime.Progress != null)
                        return Runtime.Progress;
                    if (Daemon.IsRunning)
                        return "The KTrapIC compiler is running.";
                    return Daemon.StartError != null
                        ? $"The KTrapIC compiler couldn't start: {Daemon.StartError}"
                        : "The KTrapIC compiler isn't running; it starts when a Kotlin program is edited.";
                case "restart":
                    Restart(false).Forget();
                    return "Restarting the KTrapIC compiler.";
                case "reinstall":
                    Restart(true).Forget();
                    return "Reinstalling the KTrapIC compiler; `ktrapic status` shows progress.";
                case "version":
                    return $"KTrapIC {ModInfo.Version}, compiler release {Runtime.ReleaseTag}";
                default:
                    return HelpText;
            }
        }

        private static async UniTaskVoid Restart(bool reinstall)
        {
            try
            {
                Daemon.Stop();
                await Daemon.Start(reinstall);
                Log.Info("The KTrapIC compiler restarted");
            }
            catch (Exception e)
            {
                Log.Error($"Restarting the KTrapIC compiler failed: {e.Message}");
            }
        }
    }
}
