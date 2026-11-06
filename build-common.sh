# Shared by build.sh and build-morphe.sh: tool paths, module compile, align and sign.
# Source this with HERE set to the repo root.

TT=$(dirname "$HERE")
APKTOOL="$TT/apktool_3.0.3.jar"
WORK=${WORK:-/d/tt-build}
SDK=${ANDROID_SDK:-$(cygpath -u "$LOCALAPPDATA")/Android/Sdk}
BT=$SDK/build-tools/36.1.0
AJAR=$SDK/platforms/android-36/android.jar
JDK_BIN=${JDK_BIN:-"/c/Program Files/Java/jdk-21.0.12.1/bin"}
KEYSTORE=$HERE/tgbtn.jks
KS_PASS=tgbtn-local
MODOUT=$WORK/mod

step() { printf '\n== %s\n' "$*"; }

# Compiles the module with tg.properties baked in, to $MODOUT/dex/classes.dex.
build_module() {
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

  step "compile module"
  mapfile -t SRCS < <(find "$HERE/src" "$MODOUT/gen" -name '*.java')
  "$JDK_BIN/javac.exe" -source 8 -target 8 -bootclasspath "$(cygpath -w "$AJAR")" -Xlint:-options \
    -encoding UTF-8 -d "$(cygpath -w "$MODOUT/classes")" "${SRCS[@]}"
  mapfile -t CLASSES < <(find "$MODOUT/classes" -name '*.class')
  java -cp "$(cygpath -w "$BT/lib/d8.jar")" com.android.tools.r8.D8 --release --min-api 24 \
    --lib "$(cygpath -w "$AJAR")" --output "$(cygpath -w "$MODOUT/dex")" "${CLASSES[@]}"
  ls -la "$MODOUT/dex"
}

# align_and_sign <unsigned apk> <output apk>; the unsigned input is removed.
align_and_sign() {
  local unsigned=$1 out=$2 aligned=$WORK/aligned.apk

  step "zipalign"
  "$BT/zipalign.exe" -P 16 -f 4 "$(cygpath -w "$unsigned")" "$(cygpath -w "$aligned")"
  rm -f "$unsigned"

  step "sign"
  if [ ! -f "$KEYSTORE" ]; then
    "$JDK_BIN/keytool.exe" -genkeypair -keystore "$(cygpath -w "$KEYSTORE")" -storepass "$KS_PASS" -keypass "$KS_PASS" \
      -alias tgbtn -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=tgbtn" >/dev/null
  fi
  java -jar "$(cygpath -w "$BT/lib/apksigner.jar")" sign --ks "$(cygpath -w "$KEYSTORE")" --ks-pass "pass:$KS_PASS" \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    --out "$(cygpath -w "$out")" "$(cygpath -w "$aligned")"
  rm -f "$aligned"
  java -jar "$(cygpath -w "$BT/lib/apksigner.jar")" verify "$(cygpath -w "$out")"

  step "done"
  ls -la "$out"
}
