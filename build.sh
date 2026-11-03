#!/usr/bin/env bash
# Builds TikTok 46.4.3 with the Telegram floating button.
#
#   mod/build.sh            full build: compile module, patch smali, rebuild, align, sign
#   WORK=/d/tt-build        where the decoded APK and outputs live (default)
#
# Needs: JDK 21 (javac/keytool), Android SDK build-tools 36.1.0 + platform android-36, python.
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
TT=$(dirname "$HERE")
APK_IN="$TT/TikTok [46.4.3].apk"
APKTOOL="$TT/apktool_3.0.3.jar"
WORK=${WORK:-/d/tt-build}
SDK=${ANDROID_SDK:-$(cygpath -u "$LOCALAPPDATA")/Android/Sdk}
BT=$SDK/build-tools/36.1.0
AJAR=$SDK/platforms/android-36/android.jar
JDK_BIN=${JDK_BIN:-"/c/Program Files/Java/jdk-21.0.12.1/bin"}
DECODED=$WORK/apk
OUT_APK=${OUT_APK:-$WORK/TikTok-46.4.3-tgbtn.apk}
KEYSTORE=$HERE/tgbtn.jks
KS_PASS=tgbtn-local

step() { printf '\n== %s\n' "$*"; }

# ── 1. Config.java from tg.properties ──────────
step "config"
PROPS=${PROPS:-$HERE/tg.properties}
[ -f "$PROPS" ] || { echo "missing $PROPS"; exit 1; }
prop() { grep -E "^$1=" "$PROPS" | head -1 | cut -d= -f2- | tr -d '\r'; }
SERVER_URL=$(prop SERVER_URL)
SECRET=$(prop SECRET)
SERVER_URL=${SERVER_URL%/}
if [[ "$SERVER_URL" == *VPS_IP* && "${ALLOW_PLACEHOLDER:-0}" != 1 ]]; then
  echo "SERVER_URL in tg.properties is still the placeholder ($SERVER_URL)"; exit 1
fi
[[ ${#SECRET} -ge 32 ]] || { echo "SECRET must be at least 32 characters"; exit 1; }
[[ "$SERVER_URL$SECRET" != *'"'* && "$SERVER_URL$SECRET" != *'\'* ]] || { echo "quotes/backslashes not allowed"; exit 1; }

MODOUT=$WORK/mod
rm -rf "$MODOUT"
mkdir -p "$MODOUT/gen/com/rigaz/tgbtn" "$MODOUT/classes" "$MODOUT/dex"
cat > "$MODOUT/gen/com/rigaz/tgbtn/Config.java" <<EOF
package com.rigaz.tgbtn;

final class Config {
    static final String SERVER_URL = "$SERVER_URL";
    static final String SECRET = "$SECRET";

    private Config() {
    }
}
EOF
echo "server: $SERVER_URL"

# ── 2. Compile the module to a dex ─────────────
step "compile module"
mapfile -t SRCS < <(find "$HERE/src" "$MODOUT/gen" -name '*.java')
"$JDK_BIN/javac.exe" -source 8 -target 8 -bootclasspath "$(cygpath -w "$AJAR")" -Xlint:-options \
  -encoding UTF-8 -d "$(cygpath -w "$MODOUT/classes")" "${SRCS[@]}"
mapfile -t CLASSES < <(find "$MODOUT/classes" -name '*.class')
java -cp "$(cygpath -w "$BT/lib/d8.jar")" com.android.tools.r8.D8 --release --min-api 24 \
  --lib "$(cygpath -w "$AJAR")" --output "$(cygpath -w "$MODOUT/dex")" "${CLASSES[@]}"
ls -la "$MODOUT/dex"

# ── 3. Decode once with apktool (smali only, resources stay raw) ──
if [ ! -f "$DECODED/apktool.yml" ]; then
  step "apktool decode (one time)"
  java -Xmx4g -jar "$(cygpath -w "$APKTOOL")" d -r -f -o "$(cygpath -w "$DECODED")" "$(cygpath -w "$APK_IN")"
fi

# ── 4. Patch smali (restores pristine copies first, so reruns are clean) ──
step "patch smali"
PATCHED=$(python -I "$HERE/patch_smali.py" "$DECODED")
echo "patched dex: $PATCHED"

# ── 5. Lay out dex: raw originals for untouched dex, smali for patched, module last ──
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

# ── 6. Build, align, sign ──────────────────────
step "apktool build"
java -Xmx4g -jar "$(cygpath -w "$APKTOOL")" b -f -o "$(cygpath -w "$WORK/unsigned.apk")" "$(cygpath -w "$DECODED")"

step "zipalign"
"$BT/zipalign.exe" -P 16 -f 4 "$(cygpath -w "$WORK/unsigned.apk")" "$(cygpath -w "$WORK/aligned.apk")"
rm -f "$WORK/unsigned.apk"

step "sign"
if [ ! -f "$KEYSTORE" ]; then
  "$JDK_BIN/keytool.exe" -genkeypair -keystore "$(cygpath -w "$KEYSTORE")" -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -alias tgbtn -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=tgbtn" >/dev/null
fi
java -jar "$(cygpath -w "$BT/lib/apksigner.jar")" sign --ks "$(cygpath -w "$KEYSTORE")" --ks-pass "pass:$KS_PASS" \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$(cygpath -w "$OUT_APK")" "$(cygpath -w "$WORK/aligned.apk")"
rm -f "$WORK/aligned.apk"
java -jar "$(cygpath -w "$BT/lib/apksigner.jar")" verify "$(cygpath -w "$OUT_APK")"

step "done"
ls -la "$OUT_APK"
