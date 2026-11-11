#!/usr/bin/env bash
# Installs a private Android build toolchain (JDK 17, Android SDK, Gradle) under ~/android,
# without sudo. Works on Linux and WSL.
#
#   scripts/setup-build-tools.sh                    # download everything
#   scripts/setup-build-tools.sh --accept-licenses  # also accept the Android SDK licenses
#                                                   # and install the SDK packages
# Then build with:  source ~/android/env.sh && gradle assembleDebug
set -euo pipefail

ROOT="${ANDROID_TOOLS_ROOT:-$HOME/android}"
GRADLE_VERSION="8.11.1"
CMDLINE_TOOLS_ZIP="commandlinetools-linux-11076708_latest.zip"
mkdir -p "$ROOT"
cd "$ROOT"

if [ ! -x jdk17/bin/java ]; then
  echo "==> JDK 17 (Eclipse Temurin)"
  curl -fsSL -o jdk.tar.gz "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
  mkdir -p jdk17
  tar -xzf jdk.tar.gz -C jdk17 --strip-components=1
  rm jdk.tar.gz
fi
export JAVA_HOME="$ROOT/jdk17"
export PATH="$JAVA_HOME/bin:$PATH"

# `jar` (from the JDK) stands in for unzip, which isn't always installed.
if [ ! -x sdk/cmdline-tools/latest/bin/sdkmanager ]; then
  echo "==> Android command-line tools"
  curl -fsSL -o clt.zip "https://dl.google.com/android/repository/$CMDLINE_TOOLS_ZIP"
  mkdir -p sdk/cmdline-tools
  (cd sdk/cmdline-tools && jar xf "$ROOT/clt.zip" && rm -rf latest && mv cmdline-tools latest)
  chmod +x sdk/cmdline-tools/latest/bin/*
  rm clt.zip
fi

if [ ! -x "gradle-$GRADLE_VERSION/bin/gradle" ]; then
  echo "==> Gradle $GRADLE_VERSION"
  curl -fsSL -o gradle.zip "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
  jar xf gradle.zip
  chmod +x "gradle-$GRADLE_VERSION/bin/gradle"
  rm gradle.zip
fi

cat > env.sh <<EOF
export JAVA_HOME="$ROOT/jdk17"
export ANDROID_HOME="$ROOT/sdk"
export PATH="$ROOT/jdk17/bin:$ROOT/gradle-$GRADLE_VERSION/bin:$ROOT/sdk/platform-tools:\$PATH"
EOF

if [ "${1:-}" = "--accept-licenses" ]; then
  echo "==> Accepting Android SDK licenses and installing SDK packages"
  SDKM="sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=$ROOT/sdk"
  # `yes` dies of SIGPIPE once sdkmanager stops reading; that's expected, so don't let pipefail trip.
  (set +o pipefail; yes | $SDKM --licenses > /dev/null)
  $SDKM "platform-tools" "platforms;android-35" "build-tools;35.0.0"
else
  echo
  echo "Downloads done. Run again with --accept-licenses to accept the Android SDK licenses"
  echo "(view them with: $ROOT/sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=$ROOT/sdk --licenses)"
fi
echo "Toolchain ready under $ROOT. Use: source $ROOT/env.sh"
