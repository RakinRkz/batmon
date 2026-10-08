#!/usr/bin/env bash
# build-apk.sh [PROJECT_DIR]: builds PROJECT_DIR/build/<name>.apk without Gradle
# (aapt2 + javac + d8 + zipalign + apksigner).
#
# Project layout (only the manifest and src/ are required):
#   AndroidManifest.xml   package="..." on <manifest>; no <uses-sdk> needed
#   src/                  Java sources
#   res/                  resources (drawables, layouts, values, ...)
#   assets/               raw files packaged as-is
#   libs/*.jar            plain jars, or classes.jar pulled out of AARs by fetch-maven.sh
#
# Settings (environment):
#   APK_NAME (default: project dir name)  MIN_SDK (26)  TARGET_SDK (= platform)
#   VERSION_CODE (1)  VERSION_NAME (1.0)  JAVA_RELEASE (8)
#   ANDROID_MIN_TOOLCHAIN, ANDROID_PLATFORM (35), ANDROID_BUILD_TOOLS (35.0.0)
#   Release signing reads KEYSTORE, KEY_ALIAS and the KS_PASS variable; without them a per-machine debug key is used.
set -euo pipefail

project=$(cd "${1:-.}" && pwd)
toolchain=${ANDROID_MIN_TOOLCHAIN:-$HOME/.local/share/android-min-toolchain}
platform=${ANDROID_PLATFORM:-35}
bt=$toolchain/sdk/build-tools/${ANDROID_BUILD_TOOLS:-35.0.0}
android_jar=$toolchain/sdk/platforms/android-$platform/android.jar
min_sdk=${MIN_SDK:-26}
target_sdk=${TARGET_SDK:-$platform}
name=${APK_NAME:-$(basename "$project")}
export JAVA_HOME=$toolchain/jdk17
export PATH=$JAVA_HOME/bin:$PATH

die() { echo "build-apk: $*" >&2; exit 1; }
[ -x "$bt/aapt2" ] && [ -f "$android_jar" ] || die "toolchain missing in $toolchain; run setup-toolchain.sh"
[ -f "$project/AndroidManifest.xml" ] || die "no AndroidManifest.xml in $project"
[ -d "$project/src" ] || die "no src/ in $project"

out=$project/build
rm -rf "$out"
mkdir -p "$out/classes" "$out/dex" "$out/gen"

compiled=()
if [ -d "$project/res" ]; then
    "$bt/aapt2" compile --dir "$project/res" -o "$out/res.zip"
    compiled=("$out/res.zip")
fi
assets=()
[ -d "$project/assets" ] && assets=(-A "$project/assets")
"$bt/aapt2" link -I "$android_jar" --manifest "$project/AndroidManifest.xml" \
    --min-sdk-version "$min_sdk" --target-sdk-version "$target_sdk" \
    --version-code "${VERSION_CODE:-1}" --version-name "${VERSION_NAME:-1.0}" \
    ${assets[@]+"${assets[@]}"} --java "$out/gen" -o "$out/unsigned.apk" ${compiled[@]+"${compiled[@]}"}

shopt -s nullglob
libs=("$project"/libs/*.jar)
shopt -u nullglob
classpath=$android_jar
for lib in ${libs[@]+"${libs[@]}"}; do classpath=$classpath:$lib; done

# android.jar lacks java.lang.invoke.LambdaMetafactory, so it can't be the bootclasspath:
# --release supplies java.*, android.* comes from the classpath. d8 desugars for MIN_SDK.
find "$project/src" "$out/gen" -name '*.java' >"$out/sources.txt"
javac -nowarn -encoding UTF-8 --release "${JAVA_RELEASE:-8}" -cp "$classpath" -d "$out/classes" @"$out/sources.txt"
jar cf "$out/classes.jar" -C "$out/classes" .
"$bt/d8" --release --min-api "$min_sdk" --lib "$android_jar" --output "$out/dex" \
    "$out/classes.jar" ${libs[@]+"${libs[@]}"}
(cd "$out/dex" && zip -q ../unsigned.apk classes*.dex)

"$bt/zipalign" -f -p 4 "$out/unsigned.apk" "$out/aligned.apk"
if [ -n "${KEYSTORE:-}" ]; then
    "$bt/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "${KEY_ALIAS:-release}" --ks-pass env:KS_PASS \
        --v4-signing-enabled false --out "$out/$name.apk" "$out/aligned.apk"
else
    keystore=$toolchain/debug.keystore
    [ -f "$keystore" ] || keytool -genkeypair -keystore "$keystore" -storepass android -keypass android \
        -alias debug -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug" >/dev/null 2>&1
    "$bt/apksigner" sign --ks "$keystore" --ks-pass pass:android --v4-signing-enabled false \
        --out "$out/$name.apk" "$out/aligned.apk"
    echo "note: signed with this machine's debug key; set KEYSTORE/KEY_ALIAS/KS_PASS for releases" >&2
fi
echo "built $out/$name.apk"
