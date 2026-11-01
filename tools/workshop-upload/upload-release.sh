#!/usr/bin/env bash
# Uploads a release of the IC10 Editor plugin to its Steam Workshop item (see docs/RELEASING.md).
#
#   tools/workshop-upload/upload-release.sh vX.Y.Z [--public | --item ID] [--note TEXT] [--stage-only]
#
# It uploads the plugin exactly as the GitHub release vX.Y.Z has it, KTrapIC-vX.Y.Z.zip, rather than
# a local build, so the Workshop and GitHub carry the same DLL. First it checks that the release has
# everything the plugin downloads on first use (the compiler jar and the Windows runtime), that the
# zip's About.xml and DLL are version X.Y.Z, and that the DLL is a Release build, which downloads
# from vX.Y.Z (a Debug build downloads from `dev`). It stages the zip's KTrapIC folder in
# mod/dist/workshop-release/KTrapIC and uploads that to the item the zip's About.xml names
# (WorkshopHandle), or to the item --item names, with a change note naming the version. --public
# makes the item public, so everyone subscribed gets this version. --note adds to the change note;
# --stage-only stops before the upload.
#
# Without --public the upload makes the item private, to try the release before publishing it. It
# uploads that way only to an item that is already private: on a public item, a private upload would
# replace the release its subscribers have and hide the item from them. So the first release, while
# the release item is still private, is tried on that item; a later release is tried on a private
# test item of the maintainer's, named with --item (the staged About.xml then names that item as its
# WorkshopHandle), and then uploaded with --public. --item is only for such a trial, so it can't be
# combined with --public.
#
# Windows only. Needs curl, unzip, dotnet (to build the uploader; the game installed, found as the
# plugin build finds it) and Steam running, signed in as the item's owner. Stationeers must not be
# running on any machine signed in to that account: the upload runs as the game, and Steam would end
# the other session.
set -euo pipefail

die() { echo "upload-release: $*" >&2; exit 1; }
. "$(dirname "$0")/workshop-item.sh"

tag="" visibility=private item_arg="" note_extra="" stage_only=false
while [ $# -gt 0 ]; do
  case "$1" in
    --public) visibility=public; shift ;;
    --item) [[ $# -ge 2 && "$2" =~ ^[0-9]+$ ]] || die "--item needs a Workshop item ID"; item_arg="$2"; shift 2 ;;
    --note) [ $# -ge 2 ] || die "--note needs a text"; note_extra="$2"; shift 2 ;;
    --stage-only) stage_only=true; shift ;;
    v[0-9]*) [ -z "$tag" ] || die "name one release"; tag="$1"; shift ;;
    *) die "unknown argument $1 (see the comment at the top of this script)" ;;
  esac
done
[[ "$tag" =~ ^v([0-9]+\.[0-9]+\.[0-9]+)$ ]] || die "name the release, as vX.Y.Z"
version="${BASH_REMATCH[1]}"
[ -z "$item_arg" ] || [ "$visibility" = private ] ||
  die "--item is for a private trial of the release; publish it to the release item, without --item"
version_re="${version//./[.]}"

if tasklist //FI "IMAGENAME eq rocketstation.exe" 2>/dev/null | grep -qi rocketstation; then
  die "Stationeers is running here; quit it first"
fi

root="$(git rev-parse --show-toplevel)"
cd "$root"
release="https://github.com/subitan/stationeers-ktrapic/releases/download/$tag"
# The plugin downloads these on first use; a release without them would leave it with no compiler.
for asset in ktrapic-daemon.jar ktrapic-runtime-windows-x64.zip; do
  curl -sfIL -o /dev/null "$release/$asset" || die "the release $tag has no $asset"
done

dist=mod/dist/workshop-release
stage="$dist/KTrapIC"
rm -rf "$dist"
mkdir -p "$dist"
curl -sfL -o "$dist/KTrapIC-$tag.zip" "$release/KTrapIC-$tag.zip" ||
  die "couldn't download KTrapIC-$tag.zip from the release $tag"
unzip -q "$dist/KTrapIC-$tag.zip" -d "$dist"
[ -f "$stage/KTrapIC.dll" ] && [ -f "$stage/About/About.xml" ] ||
  die "KTrapIC-$tag.zip doesn't hold KTrapIC/KTrapIC.dll and KTrapIC/About/About.xml"
about_version="$(sed -n 's|.*<Version>\(.*\)</Version>.*|\1|p' "$stage/About/About.xml")"
[ "$about_version" = "$version" ] ||
  die "the release's About.xml is version '$about_version', not $version"
# The plugin attribute's version, which the "loaded" log line shows, is stored in the DLL as UTF-8.
# LC_ALL=C makes every byte of the DLL a character the brackets can match.
LC_ALL=C grep -qaE "(^|[^0-9.])$version_re([^0-9.]|$)" "$stage/KTrapIC.dll" ||
  die "the release's DLL doesn't carry $version"
# The release the plugin downloads from is a string constant, stored as UTF-16: "vX.Y.Z" in a
# Release build, "dev" in a Debug one. Dropping the NUL bytes makes its ASCII text plain.
tr -d '\000' <"$stage/KTrapIC.dll" | LC_ALL=C grep -qaE "v$version_re([^0-9.]|$)" ||
  die "the release's DLL isn't a Release build that downloads from $tag"
handle="$(sed -n 's|.*<WorkshopHandle>\([0-9]*\)</WorkshopHandle>.*|\1|p' "$stage/About/About.xml")"
[ -n "$handle" ] || die "the release's About.xml has no WorkshopHandle"
item="${item_arg:-$handle}"
sed -i "s|<WorkshopHandle>$handle</WorkshopHandle>|<WorkshopHandle>$item</WorkshopHandle>|" "$stage/About/About.xml"
grep -q "<WorkshopHandle>$item</WorkshopHandle>" "$stage/About/About.xml" ||
  die "couldn't set the staged About.xml's WorkshopHandle"

note="$version: release $tag (https://github.com/subitan/stationeers-ktrapic/releases/tag/$tag)"
[ -z "$note_extra" ] || note="$note. $note_extra"
echo "Staged release $tag in $stage, for Workshop item $item, $visibility."
echo "Change note: $note"
if $stage_only; then exit 0; fi

dotnet build tools/workshop-upload/WorkshopUpload.csproj -c Release -nologo -v q
uploader=tools/workshop-upload/bin/Release/net48/WorkshopUpload.exe
current="$(workshop_visibility "$uploader" "$item")" || die "couldn't read Workshop item $item's visibility"
if [ "$visibility" = private ] && [ "$current" != private ]; then
  [ -z "$item_arg" ] ||
    die "Workshop item $item is $current: a trial goes only to a private item; make it private or" \
      "name another with --item"
  die "Workshop item $item is $current: uploading without --public would replace the release its" \
    "subscribers have and hide it from them. Try the release on a private test item" \
    "(upload-release.sh $tag --item ID), then publish it with --public"
fi
"$uploader" --item "$item" --content "$(cygpath -w "$root/$stage")" --note "$note" --visibility "$visibility"
