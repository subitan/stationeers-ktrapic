using System;
using Newtonsoft.Json.Linq;

namespace KTrapIC
{
    /// <summary>
    /// How the daemon compiles a program: the options of a <c>ktrapic/compile</c> request.
    /// <see cref="Compact"/> resolves labels to line numbers and drops them;
    /// <see cref="StackCalls"/> passes functions' arguments and results on the chip's stack
    /// rather than in registers.
    /// </summary>
    public readonly struct CompileOptions : IEquatable<CompileOptions>
    {
        public readonly bool Compact;
        public readonly bool StackCalls;

        public CompileOptions(bool compact, bool stackCalls = true)
        {
            Compact = compact;
            StackCalls = stackCalls;
        }

        /// <summary>The request's <c>options</c> object.</summary>
        public JObject ToJson() => new JObject { ["compact"] = Compact, ["stack_calls"] = StackCalls };

        public bool Equals(CompileOptions other) => Compact == other.Compact && StackCalls == other.StackCalls;

        public override bool Equals(object obj) => obj is CompileOptions other && Equals(other);

        public override int GetHashCode() => (Compact ? 1 : 0) | (StackCalls ? 2 : 0);

        public static bool operator ==(CompileOptions a, CompileOptions b) => a.Equals(b);

        public static bool operator !=(CompileOptions a, CompileOptions b) => !a.Equals(b);
    }
}
