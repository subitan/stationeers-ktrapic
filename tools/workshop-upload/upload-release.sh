#!/usr/bin/env bash
# Uploads a release of the IC10 Editor plugin to its Steam Workshop item (see docs/RELEASING.md).
#
#   tools/workshop-upload/upload-release.sh vX.Y.Z [--public] [--note TEXT] [--stage-only]
#
# It uploads the plugin exactly as the GitHub release vX.Y.Z has it, KTrapIC-vX.Y.Z.zip, rather than
# a local build, so the Workshop and GitHub carry the same DLL. First it checks that the release has
# everything the plugin downloads on first use (the compiler jar and the Windows runtime), and that
# the zip's About.xml and DLL are version X.Y.Z. It stages the zip's KTrapIC folder in
# mod/dist/workshop-release/KTrapIC and uploads that to the item the zip's About.xml names
# (WorkshopHandle), with a change note naming the version. The item stays private unless --public
# is given; --public makes it public, so everyone subscribed gets this version. --note adds to the
# change note; --stage-only stops before the upload.
#
# Windows only. Needs curl, unzip, dotnet (to build the uploader; the game installed, found as the
# plugin build finds it) and Steam running, signed in as the item's owner. Stationeers must not be
# running on any machine signed in to that account: the upload runs as the game, and Steam would end
# the other session.
set -euo pipefail

die() { echo "upload-release: $*" >&2; exit 1; }

tag="" visibility=private note_extra="" stage_only=false
while [ $# -gt 0 ]; do
  case "$1" in
    --public) visibility=public; shift ;;
    --note) [ $# -ge 2 ] || die "--note needs a text"; note_extra="$2"; shift 2 ;;
    --stage-only) stage_only=true; shift ;;
    v[0-9]*) [ -z "$tag" ] || die "name one release"; tag="$1"; shift ;;
    *) die "unknown argument $1 (see the comment at the top of this script)" ;;
  esac
done
[[ "$tag" =~ ^v([0-9]+\.[0-9]+\.[0-9]+)$ ]] || die "name the release, as vX.Y.Z"
version="${BASH_REMATCH[1]}"

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
grep -q "<Version>$version</Version>" "$stage/About/About.xml" ||
  die "the release's About.xml isn't version $version"
# The plugin attribute's version, which the "loaded" log line shows, is stored in the DLL as UTF-8.
grep -qaF "$version" "$stage/KTrapIC.dll" || die "the release's DLL doesn't carry $version"
item="$(sed -n 's|.*<WorkshopHandle>\([0-9]*\)</WorkshopHandle>.*|\1|p' "$stage/About/About.xml")"
[ -n "$item" ] || die "the release's About.xml has no WorkshopHandle"

note="$version: release $tag (https://github.com/subitan/stationeers-ktrapic/releases/tag/$tag)"
[ -z "$note_extra" ] || note="$note. $note_extra"
echo "Staged release $tag in $stage, for Workshop item $item, $visibility."
echo "Change note: $note"
if $stage_only; then exit 0; fi

dotnet build tools/workshop-upload/WorkshopUpload.csproj -c Release -nologo -v q
tools/workshop-upload/bin/Release/net48/WorkshopUpload.exe --item "$item" \
  --content "$(cygpath -w "$root/$stage")" --note "$note" --visibility "$visibility"
