#!/usr/bin/env bash
# Uploads a test build of the IC10 Editor plugin to a private Steam Workshop item, so the
# maintainer can try it on other machines before a release (see docs/RELEASING.md).
#
#   tools/workshop-upload/upload-test-build.sh [--item ID] [--note TEXT] [--stage-only]
#
# It builds a Debug plugin, which downloads the compiler from the rolling `dev` release, from the
# committed HEAD, which must be on origin/main. The build is versioned <About.xml's version>.<number
# of commits up to HEAD>, so every upload has its own version, shown in game as "KTrapIC 0.1.0.412
# loaded" in the BepInEx log. It stages the plugin with About/ in mod/dist/workshop-test/KTrapIC
# and uploads that, through the Steam client signed in on this machine, with a change note naming
# the version and commit, to the Workshop item --item names, or else the one About.xml names
# (WorkshopHandle). The staged About.xml names the item it is uploaded to. --note adds to the
# change note; --stage-only stops before the upload.
#
# It uploads only to a private item, so no one else gets a test build. Once the release item is
# public, test builds go to a private test item of the maintainer's, named with --item.
#
# Windows only. Needs dotnet, the game installed (found as the plugin build finds it:
# StationeersDirectory in mod/Local.Developer.props, STATIONEERS_DIR, or Steam's default path) and
# Steam running, signed in as the item's owner. Stationeers must not be running on any machine
# signed in to that account: the upload runs as the game, and Steam would end the other session.
set -euo pipefail

die() { echo "upload-test-build: $*" >&2; exit 1; }
. "$(dirname "$0")/workshop-item.sh"

item="" note_extra="" stage_only=false
while [ $# -gt 0 ]; do
  case "$1" in
    --item) [[ $# -ge 2 && "$2" =~ ^[0-9]+$ ]] || die "--item needs a Workshop item ID"; item="$2"; shift 2 ;;
    --note) [ $# -ge 2 ] || die "--note needs a text"; note_extra="$2"; shift 2 ;;
    --stage-only) stage_only=true; shift ;;
    *) die "unknown argument $1 (see the comment at the top of this script)" ;;
  esac
done

root="$(git rev-parse --show-toplevel)"
cd "$root"
about=mod/About/About.xml

[ -z "$(git status --porcelain)" ] ||
  die "commit or set aside the local changes, untracked files included, first: a test build is of a commit"
if tasklist //FI "IMAGENAME eq rocketstation.exe" 2>/dev/null | grep -qi rocketstation; then
  die "Stationeers is running here; quit it first"
fi

base="$(sed -n 's|.*<Version>\(.*\)</Version>.*|\1|p' "$about")"
[[ "$base" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "$about's Version isn't X.Y.Z: '$base'"
handle="$(sed -n 's|.*<WorkshopHandle>\([0-9]*\)</WorkshopHandle>.*|\1|p' "$about")"
[ -n "$handle" ] || die "$about has no WorkshopHandle"
[ -n "$item" ] || item="$handle"
version="$base.$(git rev-list --count HEAD)"
commit="$(git rev-parse --short HEAD)"
note="$version: test build (Debug, compiler from the dev release) of $commit, $(git log -1 --format=%s)"
[ -z "$note_extra" ] || note="$note. $note_extra"

# main has one commit per merged PR and no merge commits, so the commit count is unique there; on
# a branch it can equal another build's.
git fetch -q origin main || die "couldn't fetch origin's main, so can't check that HEAD is on it"
git merge-base --is-ancestor HEAD origin/main ||
  die "HEAD isn't on origin/main: test builds are of commits on main, the only place their versions are unique"

dotnet build mod/KTrapIC.csproj -c Debug -warnaserror -nologo -v q -p:TestVersion="$version"

stage=mod/dist/workshop-test/KTrapIC
rm -rf "$stage"
mkdir -p "$stage"
cp mod/bin/Debug/net48/KTrapIC.dll "$stage/"
# The plugin attribute's version, which the "loaded" log line shows, is stored in the DLL as UTF-8.
# LC_ALL=C makes every byte of the DLL a character the brackets can match.
LC_ALL=C grep -qaE "(^|[^0-9.])${version//./[.]}([^0-9.]|$)" "$stage/KTrapIC.dll" ||
  die "the built DLL doesn't carry $version: does mod/KTrapIC.csproj take TestVersion?"
cp -r mod/About "$stage/"
sed -i "s|<Version>$base</Version>|<Version>$version</Version>|" "$stage/About/About.xml"
grep -q "<Version>$version</Version>" "$stage/About/About.xml" || die "couldn't set the staged About.xml's version"
sed -i "s|<WorkshopHandle>$handle</WorkshopHandle>|<WorkshopHandle>$item</WorkshopHandle>|" "$stage/About/About.xml"
grep -q "<WorkshopHandle>$item</WorkshopHandle>" "$stage/About/About.xml" ||
  die "couldn't set the staged About.xml's WorkshopHandle"

echo "Staged $version ($commit) in $stage, for Workshop item $item."
echo "Change note: $note"
if $stage_only; then exit 0; fi

dotnet build tools/workshop-upload/WorkshopUpload.csproj -c Release -nologo -v q
uploader=tools/workshop-upload/bin/Release/net48/WorkshopUpload.exe
# On an item others can see, they would get the test build, and the upload would make the item
# private, hiding whatever it carried from them.
current="$(workshop_visibility "$uploader" "$item")" || die "couldn't read Workshop item $item's visibility"
[ "$current" = private ] ||
  die "Workshop item $item is $current: test builds go only to a private item; name one with --item"
"$uploader" --item "$item" --content "$(cygpath -w "$root/$stage")" --note "$note" --visibility private
