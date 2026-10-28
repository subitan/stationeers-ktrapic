using System.Collections.Generic;
using Newtonsoft.Json;

namespace KTrapIC
{
    /// <summary>
    /// The compile daemon's answer to a compile (see daemon/ in this repository; over LSP, the
    /// result of a <c>ktrapic/compile</c> request): the IC10 code and its size on success, or the
    /// first error. Lines and columns in errors are 1-based; <see cref="source_mapping"/> maps
    /// 0-based source lines to 0-based lines of <see cref="code"/>.
    /// </summary>
    public class CompileResponse
    {
        public class Problem
        {
            public string description;
            public int line;
            public int column;
        }

        public string code;
        public int num_lines;
        public int num_bytes;
        public int num_registers;
        public List<Problem> warnings;
        public Dictionary<int, List<int>> source_mapping;
        public Problem error;

        /// <summary>
        /// True for a response the compiler didn't give (it couldn't start, or stopped), which says
        /// nothing about the program.
        /// </summary>
        [JsonIgnore]
        public bool Unavailable;

        public static CompileResponse Failed(string description) =>
            new CompileResponse { error = new Problem { description = description }, Unavailable = true };
    }
}
