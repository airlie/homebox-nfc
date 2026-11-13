# Maintaining homebox-nfc

Notes for keeping the app working over time. Most of the work is reacting when something
outside the app changes: Homebox, Android, or GitHub.

## Releasing a version

1. Set `appVersion` in `gradle.properties` (e.g. `0.5.0`).
2. Update the README if behaviour changed, then commit and push to `main`.
   CI builds a signed `0.5.0-dev.<run>` APK as an artifact on every push.
3. Test that build on the phone.
4. Tag and push: `git tag -a v0.5.0 -m v0.5.0 && git push origin v0.5.0`.
   CI builds `homebox-nfc-0.5.0.apk` and attaches it to a new GitHub release with generated notes.

Version numbers: bump the minor version (0.4 → 0.5) for new features, the patch version (0.4.0 →
0.4.1) for fixes only. `versionCode` comes from the CI run number, so it always goes up.

## The signing key

Android only installs an update over the existing app if both are signed with the same key. The
keystore and its password are kept outside the repo, with a backup. The GitHub secrets
(`SIGNING_*`) hold a copy CI can use but that can't be downloaded again.

If the key is ever lost, the app has to be uninstalled before a build with a new key can be
installed. That clears its settings and any Homebox changes still waiting to be sent, so check
the sync count is zero first.

## After upgrading Homebox

Homebox is pre-1.0 and its API can change between releases (v0.27 replaced items and locations
with "entities"). The app uses `/v1/entities` (search, tree, get, update, create), tags, custom
fields, attachments, and API key login. After an upgrade, a quick check:

- [ ] **Read tag** on an existing tag shows the right name, location and fields.
- [ ] **Write one tag** writes, and the entry gets the NFC Tagged tag and NFC Tag field.
- [ ] **Inventory**: scan a location, then one item. It's marked verified.
- [ ] **Add items**: add one item (with a photo, if you use that).
- [ ] The sync count in the top bar drops back to zero.

If something fails, the sync screen shows Homebox's error message. The API calls are all in
`app/src/main/java/io/github/homeboxnfc/homebox/HomeboxClient.kt`.

## Dependency updates

Dependabot (`.github/dependabot.yml`) opens grouped update PRs once a month: build tools
(Android Gradle plugin and Kotlin), AndroidX libraries, other libraries, and GitHub Actions.
CI builds and tests each one.

- **Libraries and Actions:** merge if CI passes. For anything bigger than a patch release, install
  the dev build from the PR's artifacts and try a mode or two first.
- **Build tools:** these sometimes need the Gradle wrapper updated too (`./gradlew wrapper
  --gradle-version <x>`), or a small code fix. If the PR fails CI, that's usually why.

Security alerts are separate: turn them on under the repository's *Settings › Code security*.

## Android versions

The app is sideloaded, so Google Play's target SDK deadlines don't apply. Still, once a year (or
when the phone gets a major Android update):

- [ ] Use the app normally: NFC scanning, the camera, read-aloud, background sync.
- [ ] Raise `compileSdk` and `targetSdk` in `app/build.gradle.kts` to the current Android
      version, fix anything the build or the phone complains about, and release.

## Android developer verification (watch this)

Google is rolling out developer verification for apps installed outside Google Play on certified
Android devices: select regions from September 30, 2026, then everywhere in 2027. The signing
key will need to be registered to a verified developer. For a personal app there's a free
"limited distribution" account (no ID check, up to 20 invited devices). Before the rollout
reaches your country, register there and add this app's signing key. Details and dates have
shifted since the announcement, so check Google's current developer verification page first.

## GitHub Actions

GitHub retires old action versions and runtimes from time to time. The CI log shows a
deprecation warning months before anything fails. Dependabot's monthly Actions PR usually
handles it.

## Cloudflare Access

Changes to the Access application (policies, sign-in method, session length) can mean signing in
again under *Settings* in the app. No code change is needed unless Access changes how it hands
out the `CF_Authorization` cookie.

## Local builds

`scripts/setup-build-tools.sh` sets up JDK, Android SDK and Gradle on Linux or WSL (see the
README). `./gradlew testDebugUnitTest assembleDebug` builds a debug APK and runs the unit tests.
A debug build is signed with a different key than the releases, so it won't install over the
released app.
