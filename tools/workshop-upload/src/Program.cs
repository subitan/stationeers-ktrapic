using System;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using System.Xml.Linq;
using Steamworks;
using Steamworks.Ugc;

/// <summary>
/// Updates or creates a Stationeers Workshop item through the Steam client running on this
/// machine, signed in as the item's owner. Usage:
///   WorkshopUpload --item ID --check
///   WorkshopUpload --item ID --content DIR [--preview PNG] --note TEXT [--visibility private|friends|public]
///   WorkshopUpload --item ID --visibility private|friends|public
///   WorkshopUpload --create --content DIR [--title TEXT] [--preview PNG] --note TEXT [--visibility private|friends|public]
/// --check only reads the item's details; nothing changes. An update sets the content, preview,
/// change note and visibility, and replaces the description with DIR/About/About.xml's, headed by
/// its version; the title and tags stay as they are. --create makes a new item, as the game's
/// publisher does, from About.xml's name (or --title), description (headed by its version, as an
/// update's is) and tags, with DIR/About/Preview.png (or --preview) as its preview, and prints the
/// new item's ID. --visibility alone changes nothing else. Every upload is private unless
/// --visibility says otherwise.
/// </summary>
internal static class Program
{
    private const uint StationeersAppId = 544550;

    private static async Task<int> Main(string[] args)
    {
        string Arg(string name) =>
            args.SkipWhile(a => a != name).Skip(1).FirstOrDefault();
        bool create = args.Contains("--create");
        ulong item = 0;
        if (create ? Arg("--item") != null : !ulong.TryParse(Arg("--item"), out item))
        {
            Console.Error.WriteLine(create
                ? "--create makes a new item, so it takes no --item."
                : "Name the Workshop item with --item ID, or make a new one with --create.");
            return 2;
        }
        bool check = args.Contains("--check");
        if (create && check)
        {
            Console.Error.WriteLine("--check reads an existing item, so it takes --item, not --create.");
            return 2;
        }
        string content = Arg("--content");
        string note = Arg("--note");
        // --visibility alone changes only the item's visibility.
        bool visibilityOnly = !create && !check && content == null && Arg("--visibility") != null;
        if (!check && !visibilityOnly && (content == null || !Directory.Exists(content) || string.IsNullOrWhiteSpace(note)))
        {
            Console.Error.WriteLine(create
                ? "--create needs --content (an existing directory) and --note."
                : "An update needs --content (an existing directory) and --note, or --visibility alone.");
            return 2;
        }
        string visibility = Arg("--visibility") ?? "private";
        if (visibility != "private" && visibility != "friends" && visibility != "public")
        {
            Console.Error.WriteLine("--visibility is private, friends or public.");
            return 2;
        }

        // The item's details are read before attaching to Steam, so a bad About.xml changes nothing.
        Editor editor = default;
        XElement meta = null;
        string description = null;
        if (content != null && !check && !visibilityOnly)
        {
            string about = Path.Combine(content, "About", "About.xml");
            try
            {
                meta = XDocument.Load(about).Root;
            }
            catch (Exception e)
            {
                Console.Error.WriteLine($"Couldn't read {about}: {e.Message}");
                return 1;
            }
            description = DescriptionOf(meta);
        }
        if (create)
        {
            string about = Path.Combine(content, "About", "About.xml");
            string title = string.IsNullOrWhiteSpace(Arg("--title")) ? meta.Element("Name")?.Value.Trim() : Arg("--title");
            if (string.IsNullOrWhiteSpace(title))
            {
                Console.Error.WriteLine($"The item needs a title: {about} has no Name, and there's no --title.");
                return 1;
            }
            string preview = Arg("--preview") ?? Path.Combine(content, "About", "Preview.png");
            if (!File.Exists(preview))
            {
                Console.Error.WriteLine($"The preview {preview} doesn't exist.");
                return 1;
            }
            editor = Editor.NewCommunityFile
                .WithTitle(title)
                .WithDescription(description)
                .WithContent(Path.GetFullPath(content))
                .WithPreviewFile(Path.GetFullPath(preview))
                .WithChangeLog(note);
            foreach (var tag in meta.Element("Tags")?.Elements("Tag") ?? Enumerable.Empty<XElement>())
                editor = editor.WithTag(tag.Value.Trim());
        }

        try
        {
            SteamClient.Init(StationeersAppId, asyncCallbacks: true);
        }
        catch (Exception e)
        {
            Console.Error.WriteLine($"Couldn't attach to Steam (is it running and signed in?): {e.Message}");
            return 1;
        }
        try
        {
            Console.WriteLine($"Signed in to Steam as {SteamClient.Name} ({SteamClient.SteamId}).");
            if (!create)
            {
                var details = await SteamUGC.QueryFileAsync(item);
                if (!details.HasValue)
                {
                    Console.Error.WriteLine($"Workshop item {item} wasn't found, or isn't visible to this account.");
                    return 1;
                }
                var it = details.Value;
                Console.WriteLine($"Item {item}: \"{it.Title}\", owner {it.Owner.Id}, updated {it.Updated:u}, " +
                    $"visibility {(it.IsPrivate ? "private" : it.IsFriendsOnly ? "friends" : it.IsPublic ? "public" : "unlisted")}, " +
                    $"tags {string.Join(", ", it.Tags ?? new string[0])}.");
                if ((ulong)it.Owner.Id != (ulong)SteamClient.SteamId)
                {
                    Console.Error.WriteLine("This account doesn't own the item, so it can't update it.");
                    return 1;
                }
                if (check)
                    return 0;

                editor = new Editor(item);
                if (!visibilityOnly)
                    editor = editor.WithContent(Path.GetFullPath(content)).WithDescription(description).WithChangeLog(note);
                string preview = Arg("--preview");
                if (preview != null)
                    editor = editor.WithPreviewFile(Path.GetFullPath(preview));
            }
            switch (visibility)
            {
                case "private": editor = editor.WithPrivateVisibility(); break;
                case "friends": editor = editor.WithFriendsOnlyVisibility(); break;
                case "public": editor = editor.WithPublicVisibility(); break;
            }
            var result = await editor.SubmitAsync();
            if (!result.Success)
            {
                Console.Error.WriteLine($"The upload failed: {result.Result}." +
                    (create && result.FileId.Value != 0
                        ? $" Steam made item {result.FileId} before it failed, with no title, description or tags," +
                          " and it may not be private: delete it on the Workshop, then run --create again."
                        : ""));
                return 1;
            }
            Console.WriteLine($"{(create ? "Made" : "Updated")} item {result.FileId}, {visibility}." +
                (result.NeedsWorkshopAgreement ? " (The account still has to accept the Workshop agreement.)" : ""));
            return 0;
        }
        finally
        {
            SteamClient.Shutdown();
        }
    }

    /// <summary>
    /// About.xml's description, headed by its version, so the item's page says which version it
    /// holds; Steam has no version field of its own.
    /// </summary>
    private static string DescriptionOf(XElement meta)
    {
        string version = meta.Element("Version")?.Value.Trim();
        string description = meta.Element("Description")?.Value.Trim() ?? "";
        return string.IsNullOrEmpty(version) ? description : $"[b]Version {version}[/b]\n\n{description}";
    }
}
