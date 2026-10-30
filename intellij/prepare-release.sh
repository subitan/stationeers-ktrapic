#!/usr/bin/env bash
# Prepares a release of the IntelliJ plugin: sets its version in gradle.properties and moves
# CHANGELOG.md's Unreleased changes under that version, dated today. Commit the result in a PR;
# once it is merged, tag the merge intellij-v<version> to publish it (see docs/RELEASING.md).
#
#   intellij/prepare-release.sh 0.2.0
#   intellij/prepare-release.sh 0.2.0-beta.1    # published to the Marketplace "beta" channel
set -euo pipefail

version=${1:?usage: prepare-release.sh <version, as 0.2.0 or 0.2.0-beta.1>}
if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[a-z]+(\.[0-9]+)?)?$ ]]; then
  echo "Not a version: $version (expected 0.2.0, or 0.2.0-beta.1)" >&2
  exit 1
fi
cd "$(dirname "$0")"
if grep -q "^## \[$version\]" CHANGELOG.md; then
  echo "CHANGELOG.md already has a section for $version" >&2
  exit 1
fi
if ! awk '/^## \[Unreleased\]/ {on=1; next} /^## \[/ {on=0} on && /^- /' CHANGELOG.md | grep -q .; then
  echo "CHANGELOG.md has no Unreleased changes to release" >&2
  exit 1
fi

sed -i.bak "s/^pluginVersion=.*/pluginVersion=$version/" gradle.properties && rm gradle.properties.bak
bash ./gradlew --quiet patchChangelog
echo "Version $version: review gradle.properties and CHANGELOG.md, then commit them in a PR."
echo "Once it is merged, tag the merge commit and push the tag:"
echo "  git tag intellij-v$version <merge commit> && git push origin intellij-v$version"
