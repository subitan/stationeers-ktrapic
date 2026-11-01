# Releasing

KTrapIC releases two things, each on its own tags:

- **The IC10 Editor plugin** (`mod/`), with the compiler and Java runtime it downloads on first
  use: tags `vX.Y.Z`, built by `.github/workflows/release.yml`.
- **The IntelliJ plugin** (`intellij/`), published to JetBrains Marketplace: tags
  `intellij-vX.Y.Z`, built by `.github/workflows/intellij-release.yml`.

Every release goes through a PR like any other change. A tag is pushed only on a commit that is
on `main`.

## The IC10 Editor plugin

Every push to `main` replaces the `dev` prerelease, which Debug builds of the plugin download from.
To release version X.Y.Z:

1. In a PR, set `<Version>` in `mod/About/About.xml` to X.Y.Z, and merge it.
2. Tag the merge commit and push the tag:
   ```
   git tag vX.Y.Z <merge commit>
   git push origin vX.Y.Z
   ```
3. The Release workflow checks the tag against `About.xml`, builds a Release build of the plugin
   (which downloads from the `vX.Y.Z` release), the compiler jar and the Windows runtime, and
   publishes them as the GitHub release `vX.Y.Z`.
4. Upload the release to the Steam Workshop, on Windows, from a checkout of `main`. For the
   first release, while the item is still private, try it first with a private upload, then
   publish it:
   ```
   tools/workshop-upload/upload-release.sh vX.Y.Z
   tools/workshop-upload/upload-release.sh vX.Y.Z --public
   ```
   Once the item is public, a private upload would replace the release its subscribers have and
   hide the item from them, so the script refuses to upload to it without `--public`. Try a later
   release on the private test item (see below) instead, then publish it:
   ```
   tools/workshop-upload/upload-release.sh vX.Y.Z --item <test item>
   tools/workshop-upload/upload-release.sh vX.Y.Z --public
   ```
   With `--item` the script uploads the release's zip to that item, which must be private, with
   that item as the staged `About.xml`'s `WorkshopHandle`; it can't be combined with `--public`.

   It uploads the release's own `KTrapIC-vX.Y.Z.zip`, so the Workshop carries the same DLL as
   GitHub, after checking that the release has the compiler jar and the runtime the plugin
   downloads, and that the zip is a Release build of version X.Y.Z. It uploads as the test builds
   below do, through the Steam client signed in on that machine, with the game closed on every
   machine signed in to that account.

The Workshop item is
[3830564236](https://steamcommunity.com/sharedfiles/filedetails/?id=3830564236), recorded as
`WorkshopHandle` in `mod/About/About.xml`, so every release updates that item. Until the first
release is published it is private, for the maintainer's own testing.

Every upload replaces the item's description with the staged `About.xml`'s, headed by its
version (`X.Y.Z`, or `X.Y.Z.N` for a test build), since Steam has no version field and the change
notes are on a tab of their own. So edit the description in `mod/About/About.xml`, not on the
Workshop page: an edit made there is lost at the next upload. The title and tags stay as the
item's first upload set them.

### Test builds on the Workshop

A test build goes to a private Workshop item with one command, on Windows, from a clean checkout
of the commit to test, which must be on `main` (the script checks it against `origin/main`):

```
tools/workshop-upload/upload-test-build.sh [--item ID] [--note TEXT]
```

It uploads through the Steam client signed in on that machine, as the item's owner, so it needs no
login, and it keeps the item private. It refuses an item that isn't private, so no one else ever
gets a test build. Without `--item` it uploads to the release item, which works only until the
first release is published. After that, test builds go to a private test item of the
maintainer's, named with `--item`; the staged `About.xml` names that item as its
`WorkshopHandle`. The maintainer makes the test item once, from a staged test build:

```
tools/workshop-upload/upload-test-build.sh --stage-only
dotnet build tools/workshop-upload/WorkshopUpload.csproj -c Release
tools/workshop-upload/bin/Release/net48/WorkshopUpload.exe --create \
  --content mod/dist/workshop-test/KTrapIC --title "KTrapIC (test builds)" --note "Test item"
tools/workshop-upload/upload-test-build.sh --item <the printed ID>
```

`--create` takes the item's description and tags from the staged `About.xml`, and its preview
from `About/Preview.png`; it makes the item private, owned by the account signed in to Steam, and
prints its ID. Note that ID: it is the `--item` for every test build from then on. The stage
made without `--item` names the release item as its `WorkshopHandle`, so the last command
uploads the test build again, staged with the new item's ID. Stationeers must not be running on
any machine signed in to that account, since the upload runs as the game. Every test build:

- **is a Debug build**, which downloads the compiler from the `dev` release. A Release build
  downloads from the release `vX.Y.Z` for its version, which doesn't exist until that version is
  tagged.
- **has a version of its own**, `X.Y.Z.N`: About.xml's version, then the number of commits on
  `main` up to the build's commit. `main` has one commit per merged PR and no merge commits, so N
  is unique to that commit, and in game the BepInEx log's "KTrapIC X.Y.Z.N loaded" tells it apart
  from any other upload. The version is set only in the build; About.xml keeps `X.Y.Z`.
- **says what it is in the item's change note**: its version and commit. The item's
  description starts with the version too.

Don't upload through the game's Workshop menu (Workshop > Mods > Publish). It uploads whatever is
in the local mod folder, under that folder's version, and it makes the item public on every
upload. A first publish through it also made a second item.

## The IntelliJ plugin

### What the automation does

`intellij-release.yml` runs on a tag `intellij-vX.Y.Z`, or by hand (Actions > IntelliJ plugin
release > Run workflow).

- **Build, test and verify**, with no secrets: it checks that the tag's commit is on `main`, the
  tag against `pluginVersion` in `intellij/gradle.properties`, and that `intellij/CHANGELOG.md`
  has a section for it. It builds the compile daemon with Bazel, and builds and tests the plugin
  with it. Then it runs the Plugin Verifier against the IDE releases JetBrains recommends for the
  plugin's range (2025.1 on), as Marketplace does on upload. The unsigned plugin and the
  verifier's reports are kept as the run's artifacts. A run by hand stops here, even on a tag.
- **Sign and publish**, for a pushed tag only, in the `marketplace` environment: it waits for one of that
  environment's reviewers to approve it in the run's page. Then it signs the plugin, attaches the
  signed zip to a GitHub release `intellij-vX.Y.Z` with the version's change notes, and uploads it
  to JetBrains Marketplace. A version with a suffix, such as `0.2.0-beta.1`, goes to the
  Marketplace channel the suffix names (`beta`), and its GitHub release is a prerelease.

The plugin's change notes on Marketplace are its version's section of `intellij/CHANGELOG.md`.

### Once: setting it up

These need the maintainer's own accounts, so they are done by hand, once.

1. **A JetBrains Marketplace account.** Sign in at <https://plugins.jetbrains.com> with a JetBrains
   account, and accept the developer agreement when uploading the first plugin (step 7).
2. **A Marketplace token.** At <https://plugins.jetbrains.com/author/me/tokens>, make a token for
   uploads. Store it as `PUBLISH_TOKEN` (step 4).
3. **A signing key.** Run
   ```
   intellij/make-signing-key.sh <a new directory outside the repository>
   ```
   It asks for a password, makes a key encrypted with it and a self-signed certificate (as
   JetBrains' signing guide does) and prints the commands that store them as secrets. The key never goes in the
   repository; a new one can be made at any time, and the next release is signed with it.
4. **The `marketplace` environment.** In the repository's Settings > Environments, make an
   environment named `marketplace`:
   - Required reviewers: the maintainers who approve publishing.
   - Deployment branches and tags: selected, with the tag rule `intellij-v*`.
   - Secrets: `CERTIFICATE_CHAIN`, `PRIVATE_KEY` and `PRIVATE_KEY_PASSWORD`, with the commands
     step 3 printed, and `PUBLISH_TOKEN`:
     ```
     gh secret set PUBLISH_TOKEN --env marketplace
     ```
5. **A run by hand.** Run the workflow on `main` (Actions > IntelliJ plugin release > Run
   workflow), and check that the build job passes, the Plugin Verifier included. A run by hand
   publishes nothing, so this tries the workflow, and the hosted runner's disk space for the
   verifier's IDEs, before the first tag.
6. **The first release**, 0.1.0, as below. Marketplace takes uploads through its API only for a
   plugin it already lists, so until the repository variable `MARKETPLACE_LISTED` is `true`, the
   workflow signs the plugin and makes its GitHub release but doesn't upload it. The run's summary
   then says to upload it by hand.
7. **The first upload, by hand.** Download the signed zip from the GitHub release
   `intellij-v0.1.0`, and upload it at <https://plugins.jetbrains.com/plugin/add>. Choose the MIT
   license, with the repository as its source code and issue tracker. JetBrains reviews a new
   plugin before listing it, which takes a few working days.
8. **From then on, automatic.** Once the plugin is listed, set the variable:
   ```
   gh variable set MARKETPLACE_LISTED --body true
   ```

### Each release

To release version X.Y.Z (or X.Y.Z-beta.N):

1. Make sure `intellij/CHANGELOG.md`'s Unreleased section lists the changes since the last release.
   Each PR that changes the plugin adds its line there, under Added, Changed, Fixed or Removed.
2. On a branch, run
   ```
   intellij/prepare-release.sh X.Y.Z
   ```
   It sets `pluginVersion` in `intellij/gradle.properties` and moves the Unreleased changes under
   `## [X.Y.Z]`, dated today. Commit both in a PR, titled "IDE: Release X.Y.Z", and merge it.
3. Tag the merge commit and push the tag:
   ```
   git tag intellij-vX.Y.Z <merge commit>
   git push origin intellij-vX.Y.Z
   ```
4. In the workflow run the tag started, check the build job passed, including the Plugin
   Verifier's report. Then approve the publish job (Review deployments > `marketplace` >
   Approve). Rejecting it publishes nothing; the tag can be deleted and the release redone.
5. Check the GitHub release and, a little later, the plugin's Marketplace page. Marketplace
   checks each upload too, and may take a while to make a new version available.

A release that fails before anything reaches Marketplace, in the build job or in the publish job
before its upload, can be fixed in a new PR and tagged again. Delete the tag first, together with
the GitHub release if the publish job made one:
```
gh release delete intellij-vX.Y.Z --cleanup-tag    # if the GitHub release was made
git push origin :refs/tags/intellij-vX.Y.Z          # if it wasn't
git tag -d intellij-vX.Y.Z
```

A publish job that fails for a reason that isn't the plugin's (a network error, a wrong token, a
Marketplace outage) can be re-run from the run's page (Re-run jobs > Re-run failed jobs), after
approving it again: it signs the plugin again, replaces the zip on the GitHub release it already
made, and uploads it again. A re-run uploads the same plugin, so it doesn't help an upload
Marketplace rejects.

Once an upload has reached Marketplace, its version number is never uploaded again, even if
Marketplace rejected it. The fix is a new PR, with its line under Unreleased in
`intellij/CHANGELOG.md`, and then a release of the next version: `prepare-release.sh` and a new
tag, as in "Each release" above.

### Building a release locally

```
bazel build //daemon:ktrapic-daemon_deploy.jar
cd intellij
./gradlew buildPlugin verifyPlugin
```

builds and verifies the plugin as the workflow does. The verifier downloads several IDEs, several
GB in all, into Gradle's user home (see [intellij/README.md](../intellij/README.md)).
`./gradlew signPlugin` signs it, given the environment variables `CERTIFICATE_CHAIN`,
`PRIVATE_KEY` and `PRIVATE_KEY_PASSWORD`. `./gradlew publishPlugin` uploads it too, given
`PUBLISH_TOKEN`. Releases go through the workflow, so its approval and record stand.
