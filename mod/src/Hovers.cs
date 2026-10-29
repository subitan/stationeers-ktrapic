using System;
using System.Collections.Generic;
using System.Linq;
using Newtonsoft.Json.Linq;

namespace KTrapIC
{
    /// <summary>One function a call could be to: its declaration, its parameters, and its KDoc, if any.</summary>
    public sealed class CallSignature
    {
        public string Label;
        public IReadOnlyList<string> Parameters;
        public string Documentation;
    }

    /// <summary>
    /// The functions the call around a position could be to, and which of its arguments the
    /// position is in (0 for the first): an LSP <c>SignatureHelp</c>.
    /// </summary>
    public sealed class Signatures
    {
        public static readonly Signatures None = new Signatures(Array.Empty<CallSignature>(), 0);

        public readonly IReadOnlyList<CallSignature> All;
        public readonly int ActiveParameter;

        public Signatures(IReadOnlyList<CallSignature> all, int activeParameter)
        {
            All = all;
            ActiveParameter = activeParameter;
        }

        /// <summary>The functions in an LSP <c>SignatureHelp</c>, or none for a null one.</summary>
        public static Signatures FromLsp(JObject help)
        {
            if (help == null)
                return None;
            var all = (help["signatures"] as JArray ?? new JArray())
                .OfType<JObject>()
                .Select(s => new CallSignature
                {
                    Label = (string)s["label"] ?? "",
                    Parameters = (s["parameters"] as JArray ?? new JArray())
                        .Select(p => (p as JObject)?["label"]?.ToString() ?? "")
                        .ToList(),
                    Documentation = (s["documentation"] as JObject)?["value"]?.ToString() ?? (s["documentation"] as JValue)?.ToString(),
                })
                .ToList();
            return new Signatures(all, (int?)help["activeParameter"] ?? 0);
        }
    }

    /// <summary>The game-independent part of hovers and signature help in the Kotlin editor.</summary>
    public static class Hovers
    {
        /// <summary>How many characters wide a hover's or a signature's text is wrapped to.</summary>
        public const int Width = 72;

        /// <summary>
        /// Where the name at <paramref name="column"/> of <paramref name="line"/> starts, the
        /// column being in it, or null if there is no name there (a number is none). Hovers are
        /// asked for at that start, so moving the mouse within a name asks once.
        /// </summary>
        public static int? NameStart(string line, int column)
        {
            if (column < 0 || column >= line.Length || !IsNamePart(line[column]))
                return null;
            int start = column;
            while (start > 0 && IsNamePart(line[start - 1]))
                start--;
            return char.IsDigit(line[start]) ? (int?)null : start;
        }

        private static bool IsNamePart(char c) => char.IsLetterOrDigit(c) || c == '_';

        /// <summary>
        /// Whether to ask for signature help with the caret at <paramref name="column"/>: right
        /// after a <c>(</c> or a <c>,</c>, spaces aside, so where an argument is about to be typed.
        /// </summary>
        public static bool InArguments(string line, int column)
        {
            column = Math.Min(column, line.Length);
            int before = column - 1;
            while (before >= 0 && line[before] == ' ')
                before--;
            return before >= 0 && (line[before] == '(' || line[before] == ',');
        }
    }
}
