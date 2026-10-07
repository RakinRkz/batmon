#!/usr/bin/env bash
# Downloads a minimal Android build toolchain into $ANDROID_MIN_TOOLCHAIN
# (default ~/.local/share/android-min-toolchain): JDK 17 (Adoptium), Android command-line tools,
# platform $ANDROID_PLATFORM (default 35) and build-tools $ANDROID_BUILD_TOOLS (default 35.0.0).
# Linux x86_64 only. Idempotent: finished parts are skipped. Accepts the Android SDK licenses.
set -euo pipefail

platform=${ANDROID_PLATFORM:-35}
build_tools=${ANDROID_BUILD_TOOLS:-35.0.0}
toolchain=${ANDROID_MIN_TOOLCHAIN:-$HOME/.local/share/android-min-toolchain}

[ "$(uname -sm)" = "Linux x86_64" ] || { echo "only Linux x86_64 is supported" >&2; exit 1; }
mkdir -p "$toolchain"
cd "$toolchain"

if [ ! -x jdk17/bin/java ]; then
    echo "downloading JDK 17..."
    curl -fsSL -o jdk17.tar.gz "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
    mkdir -p jdk17 && tar -xzf jdk17.tar.gz -C jdk17 --strip-components=1 && rm jdk17.tar.gz
fi
export JAVA_HOME=$toolchain/jdk17

if [ ! -x "sdk/build-tools/$build_tools/aapt2" ] || [ ! -f "sdk/platforms/android-$platform/android.jar" ]; then
    if [ ! -x sdk/cmdline-tools/latest/bin/sdkmanager ]; then
        echo "downloading Android command-line tools..."
        curl -fsSL -o cmdline-tools.zip "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
        rm -rf sdk/cmdline-tools && mkdir -p sdk/cmdline-tools
        unzip -q cmdline-tools.zip -d sdk/cmdline-tools && mv sdk/cmdline-tools/cmdline-tools sdk/cmdline-tools/latest
        rm cmdline-tools.zip
    fi
    echo "installing platform $platform and build-tools $build_tools..."
    # `yes` dies of SIGPIPE once sdkmanager stops reading; don't let pipefail treat that as failure.
    { yes || true; } | sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root="$toolchain/sdk" \
        "platforms;android-$platform" "build-tools;$build_tools" >sdkmanager.log
fi

echo "toolchain ready in $toolchain"
