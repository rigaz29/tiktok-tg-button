# TikTok Telegram Button

A floating button added inside the TikTok Android app. Tap it on any video, photo post or
story and the link is sent to a self-hosted [tikdl](tikdl/) bot (bundled in this repo
under `tikdl/`), which downloads the post and drops it into a Telegram group. No copy-paste, no
leaving the app. A card next to the button follows the job until it is in the group.

The button is a small Java module (`com.rigaz.tgbtn`) injected into a repackaged
TikTok APK. It reads the post that is currently on screen through reflection, builds
the canonical web link, and POSTs it to the bot over a signed HTTP request.

## How it works

```
 TikTok app (this module)                     Your VPS                     Telegram
 ────────────────────────                     ────────                     ────────
  floating button
        │ tap
        ▼
  find Aweme on screen  ──┐
  (innermost pager +      │  reflection, no TikTok API calls
   adapter.getItem())   ──┘
        │
        ▼
  build link, HMAC-sign  ──POST /submit──▶  tikdl intake  ──download──▶  group @...
  the body                 (X-Timestamp,     (yt-dlp / JS API)           (video/photo
                            X-Signature)                                  + caption)
        │
  status card  ◀──GET /status/<job>──  job progress
```

Two halves, both in this repo:

- the **client** (repo root): the in-app button and the build tooling.
- **[tikdl/](tikdl/)** is the server: a Telegram bot with an HTTP intake endpoint. It
  holds the bot token in its own `.env` (gitignored, never committed) and does the
  downloading. The APK never sees the bot token.

### Why the request can be plain HTTP

The phone and the server share a secret (`SECRET` in `tg.properties`,
`INTAKE_SECRET` in tikdl's `.env`). The module signs every request as
`HMAC-SHA256(secret, "<timestamp>.<body>")` and sends `X-Timestamp` / `X-Signature`.
The secret itself never goes on the wire, so TLS is not required to keep it safe. The
server rejects anything unsigned, outside a 5-minute window, or replayed. See
`Submit.java` here and the HTTP-intake section of `tikdl/CLAUDE.md` for the matching
verification. Status reads are signed the same way, over `status:<job>`.

An observer on the network can still see which links you send, and the replies are not
signed, so this protects the secret and the group rather than your privacy.

## Repo layout

```
src/com/rigaz/tgbtn/
  Ttg.java            entry point; init() is injected into AwemeHostApplication.onCreate.
                      Registers lifecycle callbacks and attaches the button on the feed
                      and video-detail screens; owns the click handler.
  FloatButton.java    the draggable round button, drawn on the decor view with Canvas. Keeps
                      clear of the system bars and shows idle, busy, done and failed states.
  StatusBubble.java   the card next to the button that says what is happening to the post.
  Jobs.java           follows each post from the tap until the server is done with it, and
                      decides what the button and the card show.
  AwemeLocator.java   finds the Aweme object on screen through the innermost pager and its
                      adapter, with a view-tree fallback. Resilient to obfuscation.
  AwemeReader.java    reads aid / author.uniqueId / awemeType / desc off the Aweme, builds
                      the /video/ or /photo/ web link, and collects a story's direct media.
  Submit.java         HMAC-signs requests: POST <SERVER_URL>/submit and GET /status/<job>.
  Post.java           what is sent for one post.
  Notice.java         what the card shows.

patch_smali.py        injects one smali hook: Ttg.init(application) at the start of
                      AwemeHostApplication.onCreate. Keeps a .orig copy so reruns are clean.
build-morphe.sh       builds the APK that actually launches (see "Patched route" below).
build.sh              builds a modified APK without HushFeed (see "Raw route" below).
build-common.sh       tool paths, module compile, align and sign; sourced by both builds.
scripts/
  morphe_inject.py    the APK steps of build-morphe.sh: find the hook's dex, build a one-dex
                      APK for apktool, repack the result with the module added.
  dexindex.py         indexes classes across dex files; also usable on its own.
tg.properties.example copy to tg.properties and fill in SERVER_URL + SECRET.
testapp/              a tiny mock app with stub Aweme / User / VerticalViewPager classes,
                      to exercise the locator/reader logic without TikTok.

tikdl/                the server, a git submodule -> github.com/rigaz29/tikdl: the
                      Telegram bot and its signed HTTP intake. Runs on your VPS, holds
                      the bot token in its own .env. See tikdl/CLAUDE.md for setup.
```

`tikdl/` is a git submodule, so clone with `--recursive`:

```sh
git clone --recursive https://github.com/rigaz29/tiktok-tg-button.git
# already cloned? pull the submodule in with:
git submodule update --init
```

## Configuration

```sh
cp tg.properties.example tg.properties
# edit tg.properties: set SERVER_URL to your bot's address and SECRET to a long random
# value that matches INTAKE_SECRET on the server (openssl rand -hex 32).
```

`tg.properties` is gitignored. The build reads it and bakes a `Config.java` with the
values into the module dex; nothing secret is kept in source.

## Build

The module itself is version-independent — it uses only reflection and survives TikTok's
obfuscation. The hard part is getting a repackaged TikTok to actually launch, because
modern TikTok kills itself when it detects a changed signature (anti-tamper). There are
two routes. Both scripts are written for Git Bash on Windows (`cygpath`, `.exe` tools).

### Raw route (`build.sh`) — simple, but blocked by anti-tamper on recent builds

`build.sh` compiles the module to a dex, decodes the target APK with apktool, injects the
smali hook, rebuilds, zipaligns and signs with a local key.

```sh
WORK=/d/tt-build ./build.sh
```

Requirements: JDK 21, Android SDK build-tools 36.1.0 + platform android-36, python,
apktool, and the target `TikTok [46.4.3].apk` next to the repo. A local signing keystore
(`tgbtn.jks`) is generated on first run if missing.

This produces a valid APK, and the module loads (`adb logcat` shows `tgbtn: installed`),
but on recent TikTok versions the app self-kills at the splash screen: re-signing with a
non-original key trips TikTok's signature check. Use this route only against a build with
no such check, or in combination with something that neutralizes it — which is the next
route.

### Patched route (`build-morphe.sh`) — runs on current TikTok

[HushFeed](https://github.com/SysAdminDoc/hushfeed) is a Morphe/ReVanced-style patch
bundle for TikTok. Its default "Look like the store app" patch neutralizes the
anti-tamper, so a re-signed build launches normally. `build-morphe.sh` lets HushFeed
produce a runnable TikTok and adds this module to the result.

```sh
WORK=/d/tt-build ./build-morphe.sh
```

Requirements: everything the raw route needs except the 46.4.3 APK, plus the inputs
below. Each one can be overridden through the environment.

| Variable  | Default                                                   |
|-----------|-----------------------------------------------------------|
| `APKM`    | `com.zhiliaoapp.musically_47.6.3-*.apkm` next to the repo |
| `MORPHE`  | `$WORK/morphe/morphe-desktop-1.20.0-all.jar`              |
| `PATCHES` | `$WORK/morphe/patches-0.80.0.mpp`                         |
| `OUT_APK` | `$WORK/morphe/tiktok-tgbtn-final.apk`                     |

What it does:

1. Compiles the module.
2. Runs Morphe with `--unsigned`, because signing inside Morphe can fail on JDK 21 with a
   BouncyCastle/JCE error. The result is kept as `tiktok-hushfeed-unsigned.apk` and reused
   until the bundle or the patches change.
3. Finds the dex that defines `AwemeHostApplication`. HushFeed changes that class, so it
   ends up in a different dex than in the stock app, and the index can shift between
   TikTok/HushFeed versions.
4. Adds the hook to that one dex through a small single-dex apktool project, so the rest
   of the app never has to be decoded.
5. Repacks the APK with that dex replaced and the module appended as the next
   `classesN.dex`, then aligns and signs it (v1+v2+v3) with `tgbtn.jks`.

Install the result; it launches, survives the anti-tamper check, and logs
`tgbtn: installed`.

> Installing a repackaged TikTok replaces the Play Store one (different signature), so the
> existing login and drafts are lost. Expect to log in again.

## Using it

1. Make sure your tikdl bot is running and its intake is reachable
   (`GET <SERVER_URL>/health` returns `{"ok":true}`).
2. Open TikTok and go to the feed. The button appears on the right. Drag it anywhere
   between the status bar and the navigation bar; it settles on the nearest side and stays
   there until you leave the app. Opening the app again puts it back at its default spot.
3. Tap it on a video, photo post or story. The card next to the button shows the job's
   stage, then what arrived in the group, how big it is, how it was fetched and how long it
   took. If the download fails, the card gives the reason and the link stays in the group
   so you can retry. Tap the card to dismiss it.

Stories work too. A story's web link is enough most of the time; for the ones it cannot
reach, the app also sends the story's direct media URLs and the server falls back to those.

The card needs a tikdl that has `/status`. With an older bot the button still sends, and the
card stops at "Terkirim ke server".

User-facing text is in Indonesian; code and comments are in English.

## Security notes

- The Telegram bot token lives only in the server's `.env`, never in the APK.
- `SECRET` (the HMAC key) and the signing keystore are gitignored; only placeholders ship
  here. Rotate `SECRET` on both sides if it ever leaks.
- Anyone holding `SECRET` and the server address can queue downloads to your group, so
  treat it like a password.

## Status

Tested end to end on TikTok 47.6.3 (Pixel 8): the patched build launches, and a tap on the
button gets the post downloaded and uploaded to the Telegram group.
