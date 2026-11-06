#!/usr/bin/env bash
# Builds a TikTok that launches, with the Telegram floating button.
#
# TikTok kills a build.sh APK at the splash screen because the signature changed. Here Morphe
# applies HushFeed first, which neutralizes that check, and the hook and module go on top.
#
#   WORK=/d/tt-build ./build-morphe.sh
#
#   APKM       TikTok bundle (default: the 47.6.3 .apkm next to the repo)
#   MORPHE     morphe-desktop jar (default: $WORK/morphe/morphe-desktop-1.20.0-all.jar)
#   PATCHES    HushFeed bundle (default: $WORK/morphe/patches-0.80.0.mpp)
#   OUT_APK    result (default: $WORK/morphe/tiktok-tgbtn-final.apk)
#
# Needs: what build.sh needs, plus the Morphe jar and the HushFeed .mpp.
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/build-common.sh"

# Must match the class in patch_smali.py.
HOOK_CLASS=com/ss/android/ugc/aweme/app/host/AwemeHostApplication

STAGE=$WORK/morphe
APKM=${APKM:-$(ls "$TT"/com.zhiliaoapp.musically_47.6.3-*.apkm 2>/dev/null | head -1 || true)}
MORPHE=${MORPHE:-$STAGE/morphe-desktop-1.20.0-all.jar}
PATCHES=${PATCHES:-$STAGE/patches-0.80.0.mpp}
OUT_APK=${OUT_APK:-$STAGE/tiktok-tgbtn-final.apk}
HUSHFEED=$STAGE/tiktok-hushfeed-unsigned.apk
MINI=$STAGE/mini
INJECT=("python" "-I" "$(cygpath -w "$HERE/scripts/morphe_inject.py")")

for input in APKM MORPHE PATCHES; do
  [ -f "${!input}" ] || { echo "missing $input: ${!input:-not set}"; exit 1; }
done
mkdir -p "$STAGE"

# ── 1. Compile the module to a dex ─────────────
build_module

# ── 2. Apply HushFeed (minutes, so reused until its inputs change) ──
if [ ! -f "$HUSHFEED" ] || [ "$APKM" -nt "$HUSHFEED" ] || [ "$PATCHES" -nt "$HUSHFEED" ]; then
  step "morphe patch"
  # --unsigned: signing inside morphe fails on JDK 21 with "JCE cannot authenticate the provider BC".
  java -jar "$(cygpath -w "$MORPHE")" patch "$(cygpath -w "$APKM")" --patches="$(cygpath -w "$PATCHES")" \
    --unsigned -o "$(cygpath -w "$STAGE/hushfeed-partial.apk")" -t "$(cygpath -w "$STAGE/tmp")"
  mv "$STAGE/hushfeed-partial.apk" "$HUSHFEED"
else
  step "morphe patch: reusing $HUSHFEED"
fi

# ── 3. Hook the dex that holds AwemeHostApplication ──
# HushFeed changes this class, so it lands in a new dex. Only that dex goes through apktool,
# so the rest of the app never has to be decoded.
step "patch smali"
DEX=$("${INJECT[@]}" locate "$(cygpath -w "$HUSHFEED")" "$HOOK_CLASS")
echo "hook target: $DEX"
rm -rf "$MINI"
mkdir -p "$MINI"
"${INJECT[@]}" mini "$(cygpath -w "$HUSHFEED")" "$DEX" "$(cygpath -w "$MINI/mini.apk")"
java -Xmx4g -jar "$(cygpath -w "$APKTOOL")" d -r -f -o "$(cygpath -w "$MINI/dec")" "$(cygpath -w "$MINI/mini.apk")"
python -I "$HERE/patch_smali.py" "$(cygpath -w "$MINI/dec")" >/dev/null
java -Xmx4g -jar "$(cygpath -w "$APKTOOL")" b -f -o "$(cygpath -w "$MINI/patched.apk")" "$(cygpath -w "$MINI/dec")"

# ── 4. Swap the hooked dex in and append the module ──
step "repack"
MODDEX=$("${INJECT[@]}" repack "$(cygpath -w "$HUSHFEED")" "$DEX" "$(cygpath -w "$MINI/patched.apk")" \
  "$(cygpath -w "$MODOUT/dex/classes.dex")" "$(cygpath -w "$STAGE/final-unsigned.apk")")
echo "module -> $MODDEX"
rm -rf "$MINI"

# ── 5. Align and sign ──────────────────────────
align_and_sign "$STAGE/final-unsigned.apk" "$OUT_APK"
