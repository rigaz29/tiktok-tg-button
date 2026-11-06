#!/usr/bin/env bash
# Builds TikTok 46.4.3 with the Telegram floating button.
#
#   mod/build.sh            full build: compile module, patch smali, rebuild, align, sign
#   WORK=/d/tt-build        where the decoded APK and outputs live (default)
#
# Needs: JDK 21 (javac/keytool), Android SDK build-tools 36.1.0 + platform android-36, python.
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/build-common.sh"

APK_IN="$TT/TikTok [46.4.3].apk"
DECODED=$WORK/apk
OUT_APK=${OUT_APK:-$WORK/TikTok-46.4.3-tgbtn.apk}

# ── 1. Compile the module to a dex ─────────────
build_module

# ── 2. Decode once with apktool (smali only, resources stay raw) ──
if [ ! -f "$DECODED/apktool.yml" ]; then
  step "apktool decode (one time)"
  java -Xmx4g -jar "$(cygpath -w "$APKTOOL")" d -r -f -o "$(cygpath -w "$DECODED")" "$(cygpath -w "$APK_IN")"
fi

# ── 3. Patch smali (restores pristine copies first, so reruns are clean) ──
step "patch smali"
PATCHED=$(python -I "$HERE/patch_smali.py" "$DECODED")
echo "patched dex: $PATCHED"

# ── 4. Lay out dex: raw originals for untouched dex, smali for patched, module last ──
step "dex layout"
rm -f "$DECODED"/*.dex
TMPDEX=$WORK/orig-dex
if [ ! -d "$TMPDEX" ]; then
  mkdir -p "$TMPDEX"
  unzip -q -o "$APK_IN" 'classes*.dex' -d "$TMPDEX"
fi
COUNT=0
for f in "$TMPDEX"/classes*.dex; do
  name=$(basename "$f")
  COUNT=$((COUNT + 1))
  if ! grep -qx "$name" <<<"$PATCHED"; then cp "$f" "$DECODED/$name"; fi
done
MODDEX=classes$((COUNT + 1)).dex
cp "$MODOUT/dex/classes.dex" "$DECODED/$MODDEX"
echo "$COUNT original dex, module -> $MODDEX"

# ── 5. Build, align, sign ──────────────────────
step "apktool build"
java -Xmx4g -jar "$(cygpath -w "$APKTOOL")" b -f -o "$(cygpath -w "$WORK/unsigned.apk")" "$(cygpath -w "$DECODED")"

align_and_sign "$WORK/unsigned.apk" "$OUT_APK"
