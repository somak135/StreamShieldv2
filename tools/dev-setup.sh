#!/usr/bin/env bash
# One-time setup of a fresh checkout (inside the dev container or any machine with the SDK):
#   - debug.keystore: app/build.gradle.kts signs debug builds with it; it is git-ignored
#   - gradlew: the repository only has gradle-wrapper.properties
#   - local.properties: points Gradle at the Android SDK
set -euo pipefail
cd "$(dirname "$0")/.."

if [ ! -f debug.keystore ]; then
    echo "Creating debug.keystore"
    keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US" > /dev/null 2>&1
fi

if [ ! -f local.properties ] && [ -n "${ANDROID_HOME:-}" ]; then
    echo "sdk.dir=${ANDROID_HOME}" > local.properties
fi

if [ ! -x gradlew ]; then
    echo "Generating the Gradle wrapper"
    gradle wrapper --gradle-version 9.3.1 --quiet
fi

echo "Ready. Next: ./gradlew assembleDebug testDebugUnitTest"
