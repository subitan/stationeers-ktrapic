using System;
using System.IO;

/// <summary>
/// The compile daemon the tests run: the jar given by KTRAPIC_DAEMON_JAR, run with JAVA_HOME's
/// java, or the java on the path.
/// </summary>
internal static class TestDaemon
{
    public static string Jar
    {
        get
        {
            string jar = Environment.GetEnvironmentVariable("KTRAPIC_DAEMON_JAR")
                ?? throw new InvalidOperationException("Set KTRAPIC_DAEMON_JAR to a built ktrapic-daemon_deploy.jar");
            // java would resolve a relative path against the test's working directory, its build output.
            if (!Path.IsPathRooted(jar) || !File.Exists(jar))
                throw new InvalidOperationException($"KTRAPIC_DAEMON_JAR must be the absolute path of a jar that exists: {jar}");
            return jar;
        }
    }

    public static string Java
    {
        get
        {
            string javaHome = Environment.GetEnvironmentVariable("JAVA_HOME");
            return javaHome == null
                ? "java"
                : Path.Combine(javaHome, "bin", OperatingSystem.IsWindows() ? "java.exe" : "java");
        }
    }
}
