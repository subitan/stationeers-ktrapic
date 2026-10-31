using System;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Threading;
using Cysharp.Threading.Tasks;
using Newtonsoft.Json.Linq;
using UnityEngine.Networking;

namespace KTrapIC
{
    /// <summary>
    /// The Java runtime and compiler jar the compile daemon runs on. They aren't in the mod: on first
    /// use they're downloaded from this repository's GitHub release for the mod's version (the
    /// rolling <c>dev</c> release for a Debug build) into BepInEx's cache, and fetched again when the
    /// release's files change. The <c>JavaPath</c> and <c>DaemonJarPath</c> settings use local
    /// files instead.
    /// </summary>
    internal static class Runtime
    {
        private const string Repository = "subitan/stationeers-ktrapic";
        private const string JarAsset = "ktrapic-daemon.jar";

#if DEBUG
        public const string ReleaseTag = "dev";
#else
        public const string ReleaseTag = "v" + ModInfo.Version;
#endif

        public static string CacheDir => Path.Combine(BepInEx.Paths.CachePath, "ktrapic");
        private static string RuntimeDir => Path.Combine(CacheDir, "runtime");
        private static string CachedJar => Path.Combine(CacheDir, JarAsset);
        private static string StampFile => Path.Combine(CacheDir, "release.txt");

        private static bool IsWindows => Environment.OSVersion.Platform == PlatformID.Win32NT;
        private static string RuntimeAsset => $"ktrapic-runtime-{(IsWindows ? "windows" : "linux")}-x64.zip";
        private static string CachedJava => Path.Combine(RuntimeDir, "bin", IsWindows ? "java.exe" : "java");

        private static readonly SemaphoreSlim _lock = new SemaphoreSlim(1);

        /// <summary>What <see cref="Ensure"/> is doing, for the editor to show; null when idle.</summary>
        public static volatile string Progress;

        /// <summary>
        /// The java executable and daemon jar to run, downloading them first if they're missing or the
        /// release has changed (or always, when <paramref name="reinstall"/>). With no network, files
        /// already downloaded are used as they are.
        /// </summary>
        public static async UniTask<(string java, string jar)> Ensure(bool reinstall = false)
        {
            string javaOverride = Plugin.JavaPath.Value.Trim();
            string jarOverride = Plugin.DaemonJarPath.Value.Trim();
            bool needJava = javaOverride.Length == 0;
            bool needJar = jarOverride.Length == 0;
            string java = needJava ? CachedJava : javaOverride;
            string jar = needJar ? CachedJar : jarOverride;
            if (!needJava && !needJar)
                return (java, jar);

            await _lock.WaitAsync();
            try
            {
                Directory.CreateDirectory(CacheDir);
                JObject release;
                try
                {
                    release = await FetchRelease();
                }
                catch (Exception e) when (!reinstall && (!needJava || File.Exists(java)) && (!needJar || File.Exists(jar)))
                {
                    Log.Warning($"Couldn't check for a newer KTrapIC compiler, using the one already downloaded: {e.Message}");
                    return (java, jar);
                }

                var jarAsset = Asset(release, JarAsset);
                var runtimeAsset = Asset(release, RuntimeAsset);
                string stamp = string.Join(" ", ReleaseTag, Stamp(jarAsset), Stamp(runtimeAsset));
                bool current = File.Exists(StampFile) && File.ReadAllText(StampFile) == stamp;
                if (!reinstall && current && (!needJava || File.Exists(java)) && (!needJar || File.Exists(jar)))
                    return (java, jar);

                DeleteFile(StampFile);
                if (needJar)
                {
                    Progress = "Downloading the KTrapIC compiler";
                    await Download(Url(jarAsset), jar + ".part");
                    DeleteFile(jar);
                    File.Move(jar + ".part", jar);
                }
                if (needJava)
                {
                    Progress = "Downloading a Java runtime for the KTrapIC compiler";
                    string zip = Path.Combine(CacheDir, RuntimeAsset);
                    await Download(Url(runtimeAsset), zip);
                    Progress = "Unpacking the Java runtime";
                    await UniTask.SwitchToThreadPool();
                    DeleteDirectory(RuntimeDir);
                    Extract(zip, RuntimeDir);
                    DeleteFile(zip);
                }
                File.WriteAllText(StampFile, stamp);
                Log.Info($"Installed the KTrapIC compiler from release {ReleaseTag}");
                return (java, jar);
            }
            finally
            {
                Progress = null;
                _lock.Release();
            }
        }

        private static async UniTask<JObject> FetchRelease()
        {
            await UniTask.SwitchToMainThread();
            string url = $"https://api.github.com/repos/{Repository}/releases/tags/{ReleaseTag}";
            using var request = UnityWebRequest.Get(url);
            request.SetRequestHeader("User-Agent", "KTrapIC");
            await request.SendWebRequest();
            if (request.result != UnityWebRequest.Result.Success)
                throw new Exception($"Couldn't fetch {url}: {request.error}");
            return JObject.Parse(request.downloadHandler.text);
        }

        private static JToken Asset(JObject release, string name) =>
            release["assets"]?.FirstOrDefault(a => (string)a["name"] == name)
            ?? throw new Exception($"KTrapIC release {ReleaseTag} has no {name}; set JavaPath and DaemonJarPath in the KTrapIC settings to use local files.");

        private static string Stamp(JToken asset) => $"{asset["id"]}@{asset["updated_at"]}";

        private static string Url(JToken asset) => (string)asset["browser_download_url"];

        private static async UniTask Download(string url, string path)
        {
            await UniTask.SwitchToMainThread();
            Log.Info($"Downloading {url}");
            using var request = new UnityWebRequest(url, UnityWebRequest.kHttpVerbGET)
            {
                downloadHandler = new DownloadHandlerFile(path) { removeFileOnAbort = true },
            };
            request.SetRequestHeader("User-Agent", "KTrapIC");
            await request.SendWebRequest();
            if (request.result != UnityWebRequest.Result.Success)
                throw new Exception($"Couldn't download {url}: {request.error}");
        }

        /// <summary>
        /// Deletes <paramref name="path"/> if it's there. Under CrossOver (Wine) a delete can fail with
        /// a meaningless "Unknown error" (even of a file that doesn't exist, which Windows ignores), so
        /// a missing file isn't touched, and a failure is ignored if the file is gone anyway.
        /// </summary>
        private static void DeleteFile(string path)
        {
            if (!File.Exists(path))
                return;
            try
            {
                File.Delete(path);
            }
            catch (IOException) when (!File.Exists(path)) { }
        }

        /// <summary>Deletes the directory <paramref name="path"/> and all it holds, as <see cref="DeleteFile"/> does a file.</summary>
        private static void DeleteDirectory(string path)
        {
            if (!Directory.Exists(path))
                return;
            try
            {
                Directory.Delete(path, true);
            }
            catch (IOException) when (!Directory.Exists(path)) { }
        }

        /// <summary>Unzips <paramref name="zip"/> into <paramref name="dir"/>, refusing entries that would land outside it.</summary>
        private static void Extract(string zip, string dir)
        {
            string root = Path.GetFullPath(dir) + Path.DirectorySeparatorChar;
            using var archive = ZipFile.OpenRead(zip);
            foreach (var entry in archive.Entries)
            {
                string path = Path.GetFullPath(Path.Combine(dir, entry.FullName));
                if (!path.StartsWith(root, StringComparison.Ordinal))
                    throw new Exception($"Refusing to unpack {entry.FullName} outside {dir}");
                if (entry.FullName.EndsWith("/"))
                {
                    Directory.CreateDirectory(path);
                    continue;
                }
                Directory.CreateDirectory(Path.GetDirectoryName(path));
                entry.ExtractToFile(path, true);
            }
        }
    }
}
