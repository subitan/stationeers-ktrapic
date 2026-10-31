using System;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using Steamworks;
using Steamworks.Ugc;

/// <summary>
/// Updates a Stationeers Workshop item through the Steam client running on this machine, signed in
/// as the item's owner. Usage:
///   WorkshopUpload --item ID --check
///   WorkshopUpload --item ID --content DIR [--preview PNG] --note TEXT [--visibility private|friends|public]
///   WorkshopUpload --item ID --visibility private|friends|public
/// --check only reads the item's details; nothing changes. An update sets only the content, preview,
/// change note and visibility; the title, description and tags stay as they are.
/// </summary>
internal static class Program
{
    private const uint StationeersAppId = 544550;

    private static async Task<int> Main(string[] args)
    {
        string Arg(string name) =>
            args.SkipWhile(a => a != name).Skip(1).FirstOrDefault();
        if (!ulong.TryParse(Arg("--item"), out ulong item))
        {
            Console.Error.WriteLine("Name the Workshop item with --item ID.");
            return 2;
        }
        bool check = args.Contains("--check");
        string content = Arg("--content");
        string note = Arg("--note");
        // --visibility alone changes only the item's visibility.
        bool visibilityOnly = !check && content == null && Arg("--visibility") != null;
        if (!check && !visibilityOnly && (content == null || !Directory.Exists(content) || string.IsNullOrWhiteSpace(note)))
        {
            Console.Error.WriteLine("An update needs --content (an existing directory) and --note, or --visibility alone.");
            return 2;
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

            var editor = new Editor(item);
            if (!visibilityOnly)
                editor = editor.WithContent(Path.GetFullPath(content)).WithChangeLog(note);
            string preview = Arg("--preview");
            if (preview != null)
                editor = editor.WithPreviewFile(Path.GetFullPath(preview));
            switch (Arg("--visibility") ?? "private")
            {
                case "private": editor = editor.WithPrivateVisibility(); break;
                case "friends": editor = editor.WithFriendsOnlyVisibility(); break;
                case "public": editor = editor.WithPublicVisibility(); break;
                default:
                    Console.Error.WriteLine("--visibility is private, friends or public.");
                    return 2;
            }
            var result = await editor.SubmitAsync();
            if (!result.Success)
            {
                Console.Error.WriteLine($"The update failed: {result.Result}.");
                return 1;
            }
            Console.WriteLine($"Updated item {result.FileId}." +
                (result.NeedsWorkshopAgreement ? " (The account still has to accept the Workshop agreement.)" : ""));
            return 0;
        }
        finally
        {
            SteamClient.Shutdown();
        }
    }
}
