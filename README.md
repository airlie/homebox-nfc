# homebox-nfc

An Android app for putting [Homebox](https://github.com/sysadminsmedia/homebox) inventory links on NFC tags.

> **Unofficial.** This is a personal project. It isn't made, endorsed or supported by the Homebox
> project or sysadminsmedia. Please don't report problems with this app to the Homebox project.

- **Write one tag**: search for an item or location, pick it, tap a tag.
- **Batch write**: pick a Homebox location (or scan its tag). The app lists every entry under it that doesn't have a tag yet
  and walks you through them one at a time, in alphabetical order. Tap a tag, it gets written and checked, and the next one comes
  up. Progress is saved after every tag, so you can stop and pick up later. Ending the run clears the
  location, ready for the next one.
- **Add items**: for things that aren't in Homebox yet. Pick a location (or scan its tag), then tap
  blank tags: each one creates a new item there and gets written with its link. Type a name first,
  or leave it empty for a short automatic one like "Item 3f9c" to rename later. Tap one tag to add
  one item, or keep going for a batch. A switch opens the camera after each tag, and the photo is
  added to the item in Homebox. This mode needs a connection, since Homebox gives each new item its ID.
- **Inventory**: scan a location tag (or pick a location), then scan item tags. Items Homebox already
  has at that location are marked verified. Items it had somewhere else are moved here, with an Undo
  button. Each scanned item gets today's date in a **Location verified** field, plus the NFC tag and
  field if they were missing. The screen also lists the tagged items Homebox expects here that you
  haven't scanned. If you scan another location tag (say a box set up as a location), you can switch
  to it or put that box inside the previous location. A switch ("Fix tags as they're scanned", on by
  default) does what Fix tag does to each tag scanned. **Finish** sums up the location (found and not
  seen) and clears it, ready for the next one.
- **Read tag**: tap any tag to see what it holds. For a Homebox tag, that's the entry's name, where
  it is, and its **NFC Tag** and **Location verified** fields (from Homebox if it's reachable,
  otherwise from the saved data). For anything else, a plain list of the tag's records, its type and
  how full it is. Read tag doesn't change tags; it just notes anything Fix tag would fix. A switch
  on the screen ("Fix tags as they're read") does the fixing as you read, if you'd rather.
- **Fix tag**: tap tags to bring them up to date in one go: an old Homebox address is switched to the
  current one and the Android Application Record (AAR) is added if it's missing. Everything else
  on the tag is kept.

## Screen layout

Batch write, Inventory and Add items share one layout: the mode's switches at the top, then the
location (with **Change**), then the list of locations to pick from. Scanning a location's tag picks it
too.

## Moving Homebox to a new address

Change the address in Settings and the old one is remembered under **Previous addresses** (you can
also add or remove addresses there by hand). Whenever Write one tag, Batch write or Fix tag reads a
tag that links to a previous address, it rewrites the link to the current address in the same tap
and keeps the tag's other records. Inventory and Read tag do the same when their fix switch is on. Locked tags, or tags too small for the new link, are left alone and the app says so.

## Reading names aloud

Batch write, Inventory, Add items and Read tag can say the entry's name, so you can find the next thing
without looking at the screen. Switch it on with the "Read names aloud" switch on each screen, or in
Settings. Names are shortened to a quick cue: leading words like "item", "box" or "bin" are skipped
(so "Item 3f9c" is read as "3f9c"), reading stops at the first
symbol, and long names stop at the last whole word within 20 characters, so
"Box 12 - Christmas lights" is read as "12". All of this can be changed in Settings, where you can
also try a name to hear how it comes out. It uses the phone's text-to-speech engine, with an
**Extra loudness** setting (+9 dB by default) that boosts speech above the media volume for noisy places.

The screen stays in portrait by default, so it doesn't flip around while you turn the phone to reach
tags. Switch off **Keep the screen upright** in Settings to follow the phone's auto-rotate instead.

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

## How this was made

This app was written with AI assistance (Anthropic's Claude, through Claude Code).
I described what I wanted, tested each build on my own phone against my own
Homebox, and decided what went in. The AI wrote most of the code. It's a personal
project shared as is: read the code before trusting it with data you care about,
and back up your Homebox first.

## License

MIT
