# Development

Building, testing, releasing and signing StreamShield. For how the app works, read
[ARCHITECTURE.md](../ARCHITECTURE.md); for conventions and known pitfalls, [AGENTS.md](../AGENTS.md).

## Requirements

- Android Studio, or the Android SDK with platform `android-36.1`, and JDK 17 or newer.
- The build uses AGP 9.1, Gradle 9.3.1 and Kotlin 2.2, with dependencies from Google Maven, Maven
  Central and JitPack (NewPipe Extractor).
- The `shield.js` tests need Node, the `playwright` npm package with its Chromium, and `ffmpeg`.

## Set up a checkout

```sh
git clone git@github.com:streamshield-app/StreamShield.git
cd StreamShield
# Restore the shared signing key first if you have it (see "Signing" below), then:
tools/dev-setup.sh
```

The repository has `gradle/wrapper/gradle-wrapper.properties` but no `gradlew` script.
`tools/dev-setup.sh` generates `gradlew`, `local.properties` (from `ANDROID_HOME`) and, if none
exists, a new `debug.keystore`. All three are git-ignored. Android Studio also works without the
script.

## Build and test

```sh
./gradlew assembleDebug            # APK in app/build/outputs/apk/debug/
./gradlew installDebug             # install on a connected device
./gradlew testDebugUnitTest        # JVM/Robolectric unit tests
NODE_PATH=$(npm root -g) node tools/shield-js-test/shield.test.mjs   # shield.js in headless Chromium
```

See [tools/shield-js-test/README.md](../tools/shield-js-test/README.md) for the page-script tests and
[LOCAL_TESTING.md](LOCAL_TESTING.md) for the on-device test plan.

### Docker dev environment

The `Dockerfile` provides JDK 17, Gradle, the Android SDK, an Android 15 emulator, Node,
Playwright and Claude Code. On Linux, `tools/docker-dev.sh` runs it with rootless Docker (no sudo,
nothing started at boot):

```sh
tools/docker-dev.sh build                # once
tools/docker-dev.sh shell                # stops the Docker daemon again when you exit
tools/docker-dev.sh run "cd /workspace && ./gradlew assembleDebug"   # one command
```

[LOCAL_TESTING.md](LOCAL_TESTING.md) covers the emulator, inspecting the YouTube page over CDP,
and what to test.

## Continuous integration and releases

`.github/workflows/build-apk.yml` runs on every push to `main`, on pull requests, on version
tags and on demand (**Actions → Build APK → Run workflow**). Each run builds the debug APK, runs
the unit tests, and uploads the APK as the run's artifact **StreamShield-debug-apk** (downloading
artifacts needs a GitHub login). Nothing is published if the tests fail.

- **Push to `main`:** replaces the pre-release **"StreamShield (latest build)"** (tag `latest`)
  with the new `StreamShield.apk`.
- **Version tag:** publishes a permanent release.

  ```sh
  git tag v1.1.0 && git push origin v1.1.0
  ```

  The release **StreamShield v1.1.0** gets `StreamShield-v1.1.0.apk` and `StreamShield.apk`, and is
  marked as the repository's latest release, so
  `https://github.com/streamshield-app/StreamShield/releases/latest/download/StreamShield.apk`
  always serves the newest version.

CI sets `versionCode` to the workflow run number, so every build installs over the previous one,
and `versionName` to the tag without its `v` (or `dev-<commit>` for other builds). Local builds use
`versionCode 1` and `versionName "1.0"`.

On a fork, GitHub keeps Actions off until you open the **Actions** tab and enable them.

## Signing

Android installs an update only if it is signed with the same key as the installed app. If the
key is lost, every user has to uninstall (losing their library unless they exported a backup)
and install again.

- Debug builds, including the released APKs, are signed with `./debug.keystore` (git-ignored;
  alias `androiddebugkey`, store and key password `android`). The password protects nothing, so
  the file itself is the secret: anyone who has it can sign an APK that installs as an update of
  this app. Never commit it.
- CI takes the key from the repository secret `DEBUG_KEYSTORE_BASE64` (**Settings → Secrets and
  variables → Actions**). Without the secret, every CI build gets a new key and the workflow
  prints a warning.
- GitHub never shows a secret's value again, so the secret is not a backup. Keep the key in a
  password manager (the base64 text as a secure note, or the file as an attachment), plus an
  offline copy.

To check a key or an APK against the release key:

```sh
keytool -list -keystore debug.keystore -storepass android | grep SHA-256
apksigner verify --print-certs StreamShield.apk | grep SHA-256    # build-tools/<version>/apksigner
```

To restore the key on a new machine, before running `tools/dev-setup.sh`:

```sh
base64 -d > debug.keystore      # paste the base64 text, then Enter and Ctrl-D
```

To create a key for a new project (only once; it can never be changed for existing installs):

```sh
keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
  -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug"
base64 -w0 debug.keystore      # macOS: base64 -i debug.keystore; paste as DEBUG_KEYSTORE_BASE64
```

Release signing (`signingConfigs.release` in `app/build.gradle.kts`) reads `KEYSTORE_PATH`,
`STORE_PASSWORD` and `KEY_PASSWORD` from the environment; it is not used by CI.

## Google AI Studio

The app is also developed in a Google AI Studio project (see [AGENTS.md](../AGENTS.md#working-through-google-ai-studio)).
AI Studio signs its builds with its own key, which is not part of its zip export, so AI Studio
installs and GitHub builds do not install over each other. To switch a phone from one to the
other: export a backup in Settings, uninstall, install the other build and import the backup.
**Install** in AI Studio installs on a phone over USB (Chrome or Edge, USB debugging on);
**Export → Download as .zip** contains the source only.

## Application id

The application id is `com.aistudio.streamshield.vqxkpl`, the original StreamShield's, so a build
signed with the shared key updates an install of the original in place; the Room migrations keep
its watch history. The Kotlin namespace is `com.example`.

The fork [somak135/StreamShieldv2](https://github.com/somak135/StreamShieldv2) keeps its own id,
`com.aistudio.streamshield.vytkzp` (its Google AI Studio project's), so phones with the fork
update in place. Keep that line in `app/build.gradle.kts` when merging between the two
repositories.
