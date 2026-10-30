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

Publishing to the Steam Workshop isn't automated yet (#115).

## The IntelliJ plugin

### What the automation does

`intellij-release.yml` runs on a tag `intellij-vX.Y.Z`, or by hand (Actions > IntelliJ plugin
release > Run workflow).

- **Build, test and verify**, with no secrets: it checks that the tag's commit is on `main`, the
  tag against `pluginVersion` in `intellij/gradle.properties`, and that `intellij/CHANGELOG.md`
  has a section for it. It builds the compile daemon with Bazel, and builds and tests the plugin with it. Then it runs the Plugin
  Verifier against the IDE releases JetBrains recommends for the plugin's range (2025.1 on), as
  Marketplace does on upload. The unsigned plugin and the verifier's reports are kept as the run's
  artifacts. A run by hand stops here, even on a tag.
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

A release that fails before the publish job can be fixed in a new PR, then tagged again: delete the
tag first (`git push origin :refs/tags/intellij-vX.Y.Z`, then `git tag -d intellij-vX.Y.Z`).

A publish job that fails for a reason that isn't the plugin's (a network error, a wrong token, a
Marketplace outage) can be re-run from the run's page (Re-run jobs > Re-run failed jobs), after
approving it again: it signs the plugin again, replaces the zip on the GitHub release it already
made, and uploads it again. A re-run uploads the same plugin, so it doesn't help an upload
Marketplace rejects. A rejected plugin is fixed in a new PR and released as a new version.
To release the same version again instead, delete its GitHub release and its tag together
(`gh release delete intellij-vX.Y.Z --cleanup-tag`, then `git tag -d intellij-vX.Y.Z`) before
tagging again, so that the new run makes the release afresh with the new change notes.
Marketplace won't take the same version twice, so once a version is uploaded, a fix is a new
version.

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
