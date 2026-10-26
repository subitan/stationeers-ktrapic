using System;
using System.Threading;
using Cysharp.Threading.Tasks;

namespace KTrapIC
{
    /// <summary>
    /// The one compile daemon the editor tabs share, started on first use (installing the runtime
    /// first if need be) and again if it stops.
    /// </summary>
    internal static class Daemon
    {
        private static DaemonClient _client;
        private static readonly SemaphoreSlim _starting = new SemaphoreSlim(1);

        /// <summary>Why the daemon last failed to start, if it did; cleared when it starts.</summary>
        public static volatile string StartError;

        public static bool IsRunning => _client?.IsRunning == true;

        /// <summary>
        /// Compiles <paramref name="source"/> off the main thread, starting the daemon if it isn't
        /// running. Problems starting it come back as a failed response.
        /// </summary>
        public static async UniTask<CompileResponse> Compile(string source, bool compact)
        {
            DaemonClient client;
            try
            {
                client = await Start();
            }
            catch (Exception e)
            {
                return CompileResponse.Failed($"The KTrapIC compiler couldn't start: {e.Message}");
            }
            await UniTask.SwitchToThreadPool();
            return client.Compile(source, compact);
        }

        /// <summary>
        /// Compiles <paramref name="source"/> on this thread if the daemon is already running, as an
        /// export needs; null if it isn't.
        /// </summary>
        public static CompileResponse CompileNow(string source, bool compact) =>
            _client is { IsRunning: true } client ? client.Compile(source, compact) : null;

        /// <summary>The running daemon, starting it first if need be.</summary>
        public static async UniTask<DaemonClient> Start(bool reinstall = false)
        {
            if (!reinstall && _client is { IsRunning: true } running)
                return running;
            await _starting.WaitAsync();
            try
            {
                if (!reinstall && _client is { IsRunning: true } started)
                    return started;
                Stop();
                var (java, jar) = await Runtime.Ensure(reinstall);
                await UniTask.SwitchToThreadPool();
                Log.Info($"Starting the KTrapIC compiler: {java} -jar {jar}");
                _client = new DaemonClient(java, jar, line => Log.Info($"[compiler] {line}"));
                StartError = null;
                return _client;
            }
            catch (Exception e)
            {
                StartError = e.Message;
                Log.Error($"The KTrapIC compiler couldn't start: {e}");
                throw;
            }
            finally
            {
                _starting.Release();
            }
        }

        public static void Stop()
        {
            _client?.Dispose();
            _client = null;
        }
    }
}
