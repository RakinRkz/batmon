#!/usr/bin/env bash
# Build BatMon without Gradle (aapt2 + javac + d8 + apksigner, see scripts/build-apk.sh).
#   ./build.sh           build build/BatMon.apk (debug key)
#   ./build.sh install   build, then adb install -r
#   ./build.sh run       build, install and launch
#   ./build.sh release   build build/BatMon-<version>.apk signed with KEYSTORE, plus its .sha256
# Toolchain: ~/.local/share/android-min-toolchain (override with ANDROID_MIN_TOOLCHAIN).
# Release signing: KEYSTORE, KEY_ALIAS (default "release") and KS_PASS.
set -euo pipefail
cd "$(dirname "$0")"

export MIN_SDK=26 TARGET_SDK=35
export VERSION_CODE=${VERSION_CODE:-1} VERSION_NAME=${VERSION_NAME:-1.0.0}
cmd=${1:-build}

if [ "$cmd" = release ]; then
    [ -n "${KEYSTORE:-}" ] && [ -n "${KS_PASS:-}" ] || {
        echo "release: set KEYSTORE and KS_PASS (and KEY_ALIAS if not \"release\")" >&2
        exit 2
    }
    export APK_NAME="BatMon-$VERSION_NAME"
else
    export APK_NAME=BatMon
    unset KEYSTORE # debug builds always use the machine's debug key
fi
scripts/build-apk.sh .

case "$cmd" in
    build) ;;
    install) adb install -r build/BatMon.apk ;;
    run)
        adb install -r build/BatMon.apk
        adb shell am start -n com.opendroid.batmon/.MainActivity
        ;;
    release)
        apk=build/$APK_NAME.apk
        (cd build && sha256sum "$APK_NAME.apk" > "$APK_NAME.apk.sha256")
        bt=${ANDROID_MIN_TOOLCHAIN:-$HOME/.local/share/android-min-toolchain}/sdk/build-tools/35.0.0
        JAVA_HOME=${ANDROID_MIN_TOOLCHAIN:-$HOME/.local/share/android-min-toolchain}/jdk17 \
            "$bt/apksigner" verify --print-certs "$apk" | grep -E "SHA-256"
        cat "build/$APK_NAME.apk.sha256"
        ;;
    *) echo "usage: $0 [build|install|run|release]" >&2; exit 2 ;;
esac
