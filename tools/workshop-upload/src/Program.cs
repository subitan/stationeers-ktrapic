using System;
using System.IO;
using System.Linq;
using System.Text;
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
/// its version; the title stays as it is. --create makes a new item, as the game's publisher does,
/// from About.xml's name (or --title), description (headed by its version, as an update's is) and
/// tags, with DIR/About/Preview.png (or --preview) as its preview, and prints the new item's ID.
/// --visibility alone changes nothing else. Every upload is private unless --visibility says
/// otherwise. Every item gets the Mod tag (see ModTag): --create adds it to About.xml's tags, and
/// an update of an item that lacks it sets the item's tags from About.xml with Mod added; --check
/// and --visibility alone say when it's missing. Facepunch.Steamworks hands an item's tags back
/// lowercased, so the check of an existing item can't see the tag's case: an item tagged Mod in
/// another casing counts as tagged and keeps its tags. Items made by the game's publisher or by
/// this tool have the exact Mod.
/// </summary>
internal static class Program
{
    private const uint StationeersAppId = 544550;
    // The game's own Workshop list, and StationeersLaunchPad's, ask Steam only for subscribed items
    // tagged Mod, the tag the game's publisher gives every mod; an item without it is downloaded
    // but never listed. Steam's item page doesn't show it.
    private const string ModTag = "Mod";

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
            string version = meta.Element("Version")?.Value.Trim();
            string text = meta.Element("Description")?.Value.Trim();
            if (string.IsNullOrEmpty(version) || string.IsNullOrEmpty(text))
            {
                string missing = string.IsNullOrEmpty(version)
                    ? string.IsNullOrEmpty(text) ? "a Version and a Description" : "a Version"
                    : "a Description";
                Console.Error.WriteLine($"{about} needs {missing}: the item's description is made from its Version and Description.");
                return 1;
            }
            // Headed by the version so the item's page says which version it holds; Steam has no
            // version field of its own.
            description = $"[b]Version {version}[/b]\n\n{text}";
            // Steam ignores a description of 8000 bytes or more (its limit counts the terminator)
            // and still reports the upload a success.
            int bytes = Encoding.UTF8.GetByteCount(description);
            if (bytes >= 8000)
            {
                Console.Error.WriteLine($"{about}'s Description is too long: {bytes} UTF-8 bytes with its version header, and Steam takes at most 7999.");
                return 1;
            }
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
            foreach (var tag in AboutTags(meta))
                editor = editor.WithTag(tag);
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
                // A failed lookup, such as one of a deleted item, still comes back as a record: an empty
                // one (no title, owner 0) carrying the lookup's own result.
                if (it.Result != Result.OK)
                {
                    Console.Error.WriteLine($"Steam's lookup of Workshop item {item} failed: {it.Result}. " +
                        "A deleted item answers FileNotFound.");
                    return 1;
                }
                if (it.Owner.Id.Value == 0)
                {
                    Console.Error.WriteLine($"Steam returned an empty record for Workshop item {item} (owner 0), " +
                        "though the lookup reported OK.");
                    return 1;
                }
                Console.WriteLine($"Item {item}: \"{it.Title}\", owner {it.Owner.Id}, updated {it.Updated:u}, " +
                    $"visibility {(it.IsPrivate ? "private" : it.IsFriendsOnly ? "friends" : it.IsPublic ? "public" : "unlisted")}, " +
                    $"tags {string.Join(", ", it.Tags ?? new string[0])}.");
                if ((ulong)it.Owner.Id != (ulong)SteamClient.SteamId)
                {
                    Console.Error.WriteLine("This account doesn't own the item, so it can't update it.");
                    return 1;
                }
                bool tagged = it.HasTag(ModTag);
                if (!tagged)
                    Console.WriteLine($"It has no {ModTag} tag, so the game and StationeersLaunchPad don't list it" +
                        (check || visibilityOnly ? "; an update with --content adds the tag." : "."));
                if (check)
                    return 0;

                editor = new Editor(item);
                if (!visibilityOnly)
                    editor = editor.WithContent(Path.GetFullPath(content)).WithDescription(description).WithChangeLog(note);
                string preview = Arg("--preview");
                if (preview != null)
                    editor = editor.WithPreviewFile(Path.GetFullPath(preview));
                // Setting any tag replaces all the item's tags, and Facepunch.Steamworks hands them back
                // lowercased, so they're set from About.xml, as --create sets them.
                if (!tagged && !visibilityOnly)
                {
                    Console.WriteLine($"Setting the tags from About.xml, with {ModTag}.");
                    foreach (var tag in AboutTags(meta))
                        editor = editor.WithTag(tag);
                }
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
    /// About.xml's tags, led by the Mod tag. Any other casing of Mod is dropped, since the game and
    /// StationeersLaunchPad ask Steam for exactly Mod.
    /// </summary>
    private static string[] AboutTags(XElement meta)
    {
        var tags = (meta.Element("Tags")?.Elements("Tag") ?? Enumerable.Empty<XElement>())
            .Select(tag => tag.Value.Trim()).Where(tag => tag.Length > 0).ToList();
        tags.RemoveAll(tag => string.Equals(tag, ModTag, StringComparison.OrdinalIgnoreCase));
        tags.Insert(0, ModTag);
        return tags.ToArray();
    }
}
