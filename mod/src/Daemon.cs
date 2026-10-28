using System;
using System.Threading;
using Cysharp.Threading.Tasks;
using ImGuiEditor.LSP;

namespace KTrapIC
{
    /// <summary>
    /// The one compile daemon the editor tabs share, in its LSP mode, started on first use
    /// (installing the runtime first if need be) and again if it stops. Each time one has started
    /// and initialized, <see cref="Ready"/> tells the Kotlin editors, which open their documents on
    /// it.
    /// </summary>
    internal static class Daemon
    {
        private static volatile DaemonLspClient _client;
        private static readonly SemaphoreSlim _starting = new SemaphoreSlim(1);

        /// <summary>Why the daemon last failed to start, if it did; cleared when it starts.</summary>
        public static volatile string StartError;

        /// <summary>
        /// Raised on the main thread when a daemon that has just started is initialized, with its
        /// client, unless it has been replaced or stopped in the meantime.
        /// </summary>
        public static event Action<DaemonLspClient> Ready;

        /// <summary>The latest daemon started, which may have stopped since; null if none was, or after <see cref="Stop"/>.</summary>
        public static DaemonLspClient Client => _client;

        public static bool IsRunning => _client?.Connection.IsRunning == true;

        /// <summary>The running daemon, starting it first if need be.</summary>
        public static async UniTask<DaemonLspClient> Start(bool reinstall = false)
        {
            if (!reinstall && _client is { Connection: { IsRunning: true } } running)
                return running;
            await _starting.WaitAsync();
            try
            {
                if (!reinstall && _client is { Connection: { IsRunning: true } } started)
                    return started;
                Stop();
                var (java, jar) = await Runtime.Ensure(reinstall);
                await UniTask.SwitchToThreadPool();
                var startInfo = LspConnection.StartInfo(java, jar);
                Log.Info($"Starting the KTrapIC compiler: {startInfo.FileName} {startInfo.Arguments}");
                var client = new DaemonLspClient(new LspConnection(java, jar, line => Log.Info($"[compiler] {line}")));
                _client = client;
                StartError = null;
                AnnounceWhenReady(client).Forget();
                return client;
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

        /// <summary>Starts the daemon again, if <paramref name="client"/> is still the current one.</summary>
        public static void Restart(DaemonLspClient client)
        {
            if (client != _client)
                return;
            Stop();
            StartAndForget().Forget();
        }

        private static async UniTaskVoid StartAndForget()
        {
            try
            {
                await Start();
            }
            catch (Exception)
            {
                // Logged, and shown in the toolbar, by Start.
            }
        }

        private static async UniTaskVoid AnnounceWhenReady(DaemonLspClient client)
        {
            try
            {
                await client.Connection.Initialized;
            }
            catch (LspConnection.StoppedException)
            {
                // Stopped before it was ready, other than by Stop: the toolbar says why.
                if (client == _client)
                {
                    string why = client.Connection.Stopped.Result;
                    Log.Error($"The KTrapIC compiler stopped while starting: {why}");
                    StartError = why.Contains("Language Server Protocol")
                        ? why
                        : "The KTrapIC compiler stopped while starting; the BepInEx log says why.";
                }
                return;
            }
            await UniTask.SwitchToMainThread();
            if (client != _client || !client.Connection.IsRunning)
                return;
            client.MarkInitialized();
            Log.Info("The KTrapIC compiler is ready");
            Ready?.Invoke(client);
        }
    }

    /// <summary>
    /// The IC10 Editor's view of the daemon's <see cref="LspConnection"/>, which
    /// <see cref="ImGuiEditor.LSP.LSPFormatter"/> holds as its client. The plugin starts and
    /// restarts the daemon itself (<see cref="Daemon"/>), and talks to it through
    /// <see cref="Connection"/>, so this starts nothing, and none of the base class's own
    /// messaging is used: its requests (completion, semantic tokens, signature help) need its read
    /// loop, which only runs on streams it was given with <c>Init</c>, and this doesn't give it
    /// any. <see cref="KotlinFormatter"/> overrides the formatter methods that would send them.
    /// </summary>
    internal sealed class DaemonLspClient : LspClient
    {
        public DaemonLspClient(LspConnection connection) => Connection = connection;

        public LspConnection Connection { get; }

        /// <summary>Makes the base class's <c>IsInitialized</c> true, once the connection is.</summary>
        public void MarkInitialized() => _isInitialized.Set();

        /// <summary>Called by the base class when one of its own sends fails, which none do.</summary>
        public override UniTask RestartServer()
        {
            Daemon.Restart(this);
            return UniTask.CompletedTask;
        }

        public override void Dispose()
        {
            Connection.Dispose();
            base.Dispose();
        }
    }
}
