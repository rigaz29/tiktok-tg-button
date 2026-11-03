# TikTok Telegram Button

A floating button added inside the TikTok Android app. Tap it on any video or photo
post and the link is sent to a self-hosted [tikdl](tikdl/) bot (bundled in this repo
under `tikdl/`), which downloads the post and drops it into a Telegram group. No copy-paste, no
leaving the app.

The button is a small Java module (`com.rigaz.tgbtn`) injected into a repackaged
TikTok APK. It reads the post that is currently on screen through reflection, builds
the canonical web link, and POSTs it to the bot over a signed HTTP request.

## How it works

```
 TikTok app (this module)                     Your VPS                     Telegram
 ────────────────────────                     ────────                     ────────
  floating "TG" button
        │ tap
        ▼
  find Aweme on screen  ──┐
  (VerticalViewPager +    │  reflection, no TikTok API calls
   adapter.getItem())   ──┘
        │
        ▼
  build link, HMAC-sign  ──POST /submit──▶  tikdl intake  ──download──▶  group @...
  the body                 (X-Timestamp,     (yt-dlp / JS API)           (video/photo
                            X-Signature)                                  + caption)
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
verification.

## Repo layout

```
src/com/rigaz/tgbtn/
  Ttg.java            entry point; init() is injected into AwemeHostApplication.onCreate.
                      Registers lifecycle callbacks and attaches the button on the feed
                      and video-detail screens; owns the click handler.
  FloatButton.java    the 48dp draggable round "TG" button, drawn on the decor view.
                      Remembers its position; turns amber while a send is in flight.
  AwemeLocator.java   finds the Aweme object on screen via the VerticalViewPager and its
                      adapter, with a view-tree fallback. Resilient to obfuscation.
  AwemeReader.java    reads aid / author.uniqueId / awemeType / shareUrl off the Aweme and
                      builds the /video/ or /photo/ web link.
  Submit.java         HMAC-signs the body and POSTs it to <SERVER_URL>/submit.
  Post.java           tiny value holder (id + link).

patch_smali.py        injects one smali hook: Ttg.init(application) at the start of
                      AwemeHostApplication.onCreate. Keeps a .orig copy so reruns are clean.
build.sh              builds a modified APK end to end (see "Build — raw route" below).
tg.properties.example copy to tg.properties and fill in SERVER_URL + SECRET.
testapp/              a tiny mock app with stub Aweme / User / VerticalViewPager classes,
                      to exercise the locator/reader logic without TikTok.

tikdl/                the server: the Telegram bot and its signed HTTP intake. Runs on
                      your VPS, holds the bot token in tikdl/.env (gitignored). See
                      tikdl/CLAUDE.md for its architecture and setup.
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
two routes.

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

### Patched route (Morphe + HushFeed) — runs on current TikTok

[HushFeed](https://github.com/SysAdminDoc/hushfeed) is a Morphe/ReVanced-style patch
bundle for TikTok. Its default "Look like the store app" patch neutralizes the
anti-tamper, so a re-signed build launches normally. The idea is to let HushFeed produce
a runnable TikTok and inject this module's dex alongside it.

Outline (tested on TikTok 47.6.3, Pixel 8):

1. Patch the TikTok bundle with Morphe + HushFeed, unsigned:
   `java -jar morphe-desktop.jar patch <tiktok.apkm> --patches=<hushfeed.mpp> --unsigned -o tiktok-hushfeed-unsigned.apk`
   (sign-on-patch can fail on JDK 21 with a BouncyCastle/JCE error — hence `--unsigned`,
   then sign yourself with `apksigner` and your own key.)
2. Find which dex HushFeed moved `AwemeHostApplication` into (it is not `classes.dex`
   anymore). Any dex index tool works; this repo's history used `scripts/dexindex.py`.
3. Patch that one dex's `onCreate` with `patch_smali.py` (via a small single-dex apktool
   project, since the hook declaration is unchanged), and append the module dex as the
   next `classesNN.dex`.
4. `zipalign -P 16 -f 4`, then `apksigner sign` (v1+v2+v3) with your key.

Install the result; it launches, survives the anti-tamper check, and logs
`tgbtn: installed`. The only step that shifts between TikTok/HushFeed versions is the dex
index in step 2.

> Installing a repackaged TikTok replaces the Play Store one (different signature), so the
> existing login and drafts are lost. Expect to log in again.

## Using it

1. Make sure your tikdl bot is running and its intake is reachable
   (`GET <SERVER_URL>/health` returns `{"ok":true}`).
2. Open TikTok, go to the For You feed. The draggable **TG** button appears (drag to
   reposition; the spot is remembered).
3. Tap it on a video or photo post. A toast confirms the send, and the post shows up in
   the configured Telegram group a few seconds later.

User-facing toasts are in Indonesian; code and comments are in English.

## Security notes

- The Telegram bot token lives only in the server's `.env`, never in the APK.
- `SECRET` (the HMAC key) and the signing keystore are gitignored; only placeholders ship
  here. Rotate `SECRET` on both sides if it ever leaks.
- Anyone holding `SECRET` and the server address can queue downloads to your group, so
  treat it like a password.

## Status

The build pipeline and the module are proven end to end on TikTok 47.6.3 (Pixel 8): the
patched build launches and `Ttg.init` runs inside it. The server side (signed `/submit` →
download → post to the group) is tested independently.
