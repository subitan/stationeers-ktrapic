using System;
using Newtonsoft.Json.Linq;

namespace KTrapIC
{
    /// <summary>
    /// How the daemon compiles a program: the options of a <c>ktrapic/compile</c> request.
    /// <see cref="Compact"/> resolves labels to line numbers and drops them;
    /// <see cref="StackCalls"/> passes functions' arguments and results on the chip's stack
    /// rather than in registers; <see cref="TailCalls"/> compiles a function's call to itself in
    /// tail position as a jump back to its start, which takes no stack.
    /// </summary>
    public readonly struct CompileOptions : IEquatable<CompileOptions>
    {
        public readonly bool Compact;
        public readonly bool StackCalls;
        public readonly bool TailCalls;

        public CompileOptions(bool compact, bool stackCalls = true, bool tailCalls = true)
        {
            Compact = compact;
            StackCalls = stackCalls;
            TailCalls = tailCalls;
        }

        /// <summary>The request's <c>options</c> object.</summary>
        public JObject ToJson() =>
            new JObject { ["compact"] = Compact, ["stack_calls"] = StackCalls, ["tail_calls"] = TailCalls };

        public bool Equals(CompileOptions other) =>
            Compact == other.Compact && StackCalls == other.StackCalls && TailCalls == other.TailCalls;

        public override bool Equals(object obj) => obj is CompileOptions other && Equals(other);

        public override int GetHashCode() => (Compact ? 1 : 0) | (StackCalls ? 2 : 0) | (TailCalls ? 4 : 0);

        public static bool operator ==(CompileOptions a, CompileOptions b) => a.Equals(b);

        public static bool operator !=(CompileOptions a, CompileOptions b) => !a.Equals(b);
    }
}
