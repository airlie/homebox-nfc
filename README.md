# homebox-nfc

An Android app for putting [Homebox](https://github.com/sysadminsmedia/homebox) inventory links on NFC tags.

- **Write one tag**: search for an item or location, pick it, tap a tag.
- **Batch write**: pick a Homebox location (or scan its tag). The app lists every entry under it that doesn't have a tag yet
  and walks you through them one at a time. Tap a tag, it gets written and checked, and the next one comes
  up. Progress is saved after every tag, so you can stop and pick up later.
- **Inventory**: scan a location tag (or pick a location), then scan item tags. Items Homebox already
  has at that location are marked verified. Items it had somewhere else are moved here, with an Undo
  button. Each scanned item gets today's date in a **Location verified** field, plus the NFC tag and
  field if they were missing. The screen also lists the tagged items Homebox expects here that you
  haven't scanned. If you scan another location tag (say a box set up as a location), you can switch
  to it or put that box inside the previous location.
- **Fix tag**: tap a tag that already holds a URL. If it doesn't have an Android Application Record (AAR)
  for Chrome yet, the app adds one in the same tap, keeping the URL and anything else on the tag.

## Works without a connection

Storage rooms and garages often have patchy Wi-Fi, so the app doesn't need Homebox to be reachable
while you work:

- It keeps a saved copy of your Homebox locations and items (names, IDs and nesting only, about 100
  bytes per entry) and refreshes it whenever you open the app or a mode and Homebox is reachable.
- Writing tags, verifying and moving items all work from that copy, so scanning is instant.
- Homebox updates go into a queue and are sent right away if possible. If not, they're sent when the
  connection comes back: immediately if the app is open, or in the background through Android's
  WorkManager (only when there's a network; no always-on service).
- The sync icon in the top bar shows how many changes are waiting. Tap it to see them, send them now,
  or retry/discard any that Homebox refused (for example, an item that was deleted meanwhile).

For a site with no signal at all: open the app before you go (it refreshes the saved data), do the
work there, and the changes go through on their own once you're back in range.

## What gets written

Each tag gets:

1. A URI record: `https://<your-homebox>/item/<uuid>` (or `/location/<uuid>`)
2. Optionally, a text record with the entry's name, so NFC reader apps show what the tag is for
   (off by default; shortened to fit if the tag is small)
3. An Android Application Record for Chrome (or another app you choose, or none)

The AAR makes Android open the link in Chrome directly instead of showing an app chooser. Choose
**Default browser** in Settings to write the link only. Two limits come from Android itself: tags are
only read while the screen is on, and apps only launch once the phone is unlocked. On a phone without
Chrome, the AAR sends you to the Play Store.

An NTAG213 (144 bytes) is enough. A full Homebox item URL plus the AAR is about 90 bytes.

## How entries are marked in Homebox

When a tag is written, the app:

- adds the Homebox tag **NFC Tagged** to the entry (created if missing), and
- sets a custom text field **NFC Tag** to the tag's UID, e.g. `04:A2:3B:1C:55:80:00`, and
- sets **Location verified** to today's date (you're standing next to it).

Inventory also sets **Location verified** when it sees the item, and fills in **NFC Tag** if it's empty. All three names can be
changed in Settings, and leaving one empty turns it off. Batch write uses the **NFC Tagged** tag to
skip entries that are already done.

## Requirements

- Android 8.0+ with NFC (built and tested for a Pixel 7a)
- **Homebox v0.27 or newer** (the "entities" API). Older versions aren't supported.

## Getting past Cloudflare Access

If Homebox sits behind Cloudflare Access, the app supports:

| Mode | How |
| --- | --- |
| Client certificate (mTLS) | Install the cert under *Settings › Security › Encryption & credentials › Install a certificate › VPN & app user certificate*, then pick it in the app. This is the same setup the Home Assistant app uses. |
| Service token | Create one under *Zero Trust › Access › Service auth* and add a *Service Auth* policy to the Homebox application. |
| Sign in with browser | Logs in through the normal Access page in an embedded browser and keeps the `CF_Authorization` cookie. The home screen shows when the sign-in expires, and a banner with a Sign in button appears once it has. Changes made meanwhile wait on the phone. |
| None | Use this when `/api/*` isn't behind Access (for example, a bypass rule). |

Chrome handles Access on its own when a tag opens a link, so it needs the cert installed or a
normal Access login.

## Homebox login

Log in with your Homebox username and password once. The app doesn't store the password. It creates a
Homebox API key named `homebox-nfc (<phone model>)`, which you can revoke from your Homebox profile.
You can also paste an API key you created yourself.

## Installing

Download the APK from [Releases](../../releases) (or from the latest Actions run) and sideload it.

## Building

CI builds the APK on every push (see `.github/workflows/build.yml`).

To build locally on Linux or WSL without Android Studio, `scripts/setup-build-tools.sh` installs JDK 17,
the Android SDK and Gradle under `~/android` (no sudo). Its `--accept-licenses` flag accepts the Android
SDK licenses and installs the SDK packages.

```bash
scripts/setup-build-tools.sh --accept-licenses
source ~/android/env.sh
./gradlew assembleDebug   # APK lands in app/build/outputs/apk/debug/
```

### Release signing

Without signing secrets, CI signs builds with a throwaway debug key, which means each build has a
different signature and won't install over the previous one. To get stable signatures, create a keystore
once and add it as repository secrets:

```bash
keytool -genkeypair -v -keystore release.jks -alias homebox-nfc -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 release.jks   # paste into SIGNING_KEYSTORE_BASE64
```

Secrets: `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`.
Keep `release.jks` out of the repo; `.gitignore` already excludes it.

## License

MIT
