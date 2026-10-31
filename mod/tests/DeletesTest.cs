using System.IO;
using System.Linq;
using System.Runtime.CompilerServices;
using System.Text.RegularExpressions;
using Xunit;

/// <summary>
/// The plugin deletes files only through Runtime's DeleteFile and DeleteDirectory. Under CrossOver
/// (Wine) on macOS a bare File.Delete or Directory.Delete of something that isn't there throws a
/// meaningless "Unknown error" instead of doing nothing, as it does on Windows; the helpers check
/// first. This test keeps a new bare delete from creeping back in.
/// </summary>
public class DeletesTest
{
    private static readonly Regex BareDelete = new Regex(@"\b(File|Directory)\.Delete\(");

    // The helpers' own calls, the only ones allowed.
    private static readonly string[] Allowed = { "File.Delete(path);", "Directory.Delete(path, true);" };

    private static string SourceDir([CallerFilePath] string here = "") =>
        Path.Combine(Path.GetDirectoryName(here), "..", "src");

    [Fact]
    public void OnlyTheHelpersDeleteFiles()
    {
        var bare = Directory.GetFiles(SourceDir(), "*.cs", SearchOption.AllDirectories)
            .SelectMany(file => File.ReadAllLines(file).Select((line, i) => (file, line: line.Trim(), number: i + 1)))
            .Where(l => !l.line.StartsWith("//") && !l.line.StartsWith("///") && BareDelete.IsMatch(l.line))
            .Where(l => !(Path.GetFileName(l.file) == "Runtime.cs" && Allowed.Contains(l.line)))
            .Select(l => $"{Path.GetFileName(l.file)}:{l.number}: {l.line}")
            .ToList();
        Assert.True(bare.Count == 0,
            "Delete through Runtime.DeleteFile or DeleteDirectory, which work under CrossOver: " + string.Join("; ", bare));
    }
}
