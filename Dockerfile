# StreamShield development environment.
#
# Contains everything needed to build and test the app:
#   - JDK 17, Gradle 9.3.1, Android SDK (platform 36, build-tools 36, platform-tools)
#   - optional Android emulator + Android 15 system image (INSTALL_EMULATOR=true, x86_64 hosts)
#   - Node 22, Playwright + Chromium, ffmpeg (page-script tests and live YouTube checks)
#   - Claude Code CLI (INSTALL_CLAUDE=true)
#
# Build:   docker build -t streamshield-dev .
#          docker build -t streamshield-dev --build-arg INSTALL_EMULATOR=false .   # smaller, no emulator
# Run:     see docs/LOCAL_TESTING.md ("Start the container").
#
# The source is not copied into the image; mount the repository at /workspace.

FROM eclipse-temurin:17-jdk-jammy

ARG USER_UID=1000
ARG USER_GID=1000
ARG INSTALL_EMULATOR=true
ARG INSTALL_CLAUDE=true
ARG GRADLE_VERSION=9.3.1
ARG CMDLINE_TOOLS_BUILD=11076708
ARG EMULATOR_IMAGE="system-images;android-35;google_apis;x86_64"

ENV DEBIAN_FRONTEND=noninteractive \
    ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    GRADLE_HOME=/opt/gradle \
    GRADLE_USER_HOME=/home/dev/.gradle \
    ANDROID_AVD_HOME=/home/dev/.android/avd \
    PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers \
    NODE_PATH=/usr/local/lib/node_modules
ENV PATH=${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${ANDROID_HOME}/emulator:${GRADLE_HOME}/bin:${PATH}

# Base tools; the lib* packages are what the emulator needs to run headless.
RUN apt-get update && apt-get install -y --no-install-recommends \
        git curl unzip zip ca-certificates sudo ffmpeg python3 jq less procps \
        libgl1 libpulse0 libnss3 libxcomposite1 libxcursor1 libxi6 libxtst6 libasound2 \
        libxdamage1 libxrandr2 libxkbfile1 libdbus-1-3 \
    && rm -rf /var/lib/apt/lists/*

# Node 22 (official binaries, copied from the Node image).
COPY --from=node:22-bookworm-slim /usr/local/bin/node /usr/local/bin/node
COPY --from=node:22-bookworm-slim /usr/local/lib/node_modules /usr/local/lib/node_modules
RUN ln -s /usr/local/lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
    && ln -s /usr/local/lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx \
    && node --version && npm --version

# Gradle (the repository has no gradlew script; tools/dev-setup.sh generates one).
RUN curl -fsSL -o /tmp/gradle.zip "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" \
    && unzip -q /tmp/gradle.zip -d /opt \
    && mv "/opt/gradle-${GRADLE_VERSION}" "${GRADLE_HOME}" \
    && rm /tmp/gradle.zip

# Android SDK. AGP downloads anything else it needs (e.g. the android-36.1 platform) on the
# first build, which works because the licenses are accepted here.
RUN mkdir -p "${ANDROID_HOME}/cmdline-tools" \
    && curl -fsSL -o /tmp/cmdline-tools.zip \
        "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_BUILD}_latest.zip" \
    && unzip -q /tmp/cmdline-tools.zip -d "${ANDROID_HOME}/cmdline-tools" \
    && mv "${ANDROID_HOME}/cmdline-tools/cmdline-tools" "${ANDROID_HOME}/cmdline-tools/latest" \
    && rm /tmp/cmdline-tools.zip \
    && yes | sdkmanager --licenses > /dev/null \
    && sdkmanager --install "platform-tools" "platforms;android-36" "build-tools;36.0.0" \
    && if [ "${INSTALL_EMULATOR}" = "true" ]; then \
         sdkmanager --install "emulator" "${EMULATOR_IMAGE}" \
         && echo "no" | avdmanager create avd --name streamshield --package "${EMULATOR_IMAGE}" \
              --device "pixel_6" --path /opt/avd/streamshield; \
       fi

# Playwright + Chromium for the page-script tests and live checks against YouTube.
RUN npm install -g playwright@1.48.2 \
    && playwright install --with-deps chromium \
    && if [ "${INSTALL_CLAUDE}" = "true" ]; then npm install -g @anthropic-ai/claude-code; fi \
    && rm -rf /var/lib/apt/lists/* /root/.npm

# Non-root user matching the host UID so files written to the mounted repo stay yours.
# ~/.gradle and ~/.claude are created here because named volumes mounted on them take the owner
# of the directory in the image; Docker would otherwise create them as root.
RUN groupadd --gid "${USER_GID}" dev 2>/dev/null || true \
    && useradd --uid "${USER_UID}" --gid "${USER_GID}" --create-home --shell /bin/bash dev \
    && echo "dev ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/dev \
    && mkdir -p /opt/avd "${PLAYWRIGHT_BROWSERS_PATH}" \
    && chown -R dev:dev "${ANDROID_HOME}" /opt/avd "${PLAYWRIGHT_BROWSERS_PATH}" \
    && install -d -o dev -g dev "${GRADLE_USER_HOME}" /home/dev/.claude \
    && if [ -d /opt/avd/streamshield ]; then \
         mkdir -p /home/dev/.android/avd \
         && printf 'avd.ini.encoding=UTF-8\npath=/opt/avd/streamshield\ntarget=android-35\n' \
              > /home/dev/.android/avd/streamshield.ini \
         && chown -R dev:dev /home/dev/.android; \
       fi

USER dev
WORKDIR /workspace
CMD ["bash"]
