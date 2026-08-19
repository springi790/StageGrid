#!/usr/bin/env sh
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
if [ -f "$WRAPPER_JAR" ]; then
  exec java -classpath "$WRAPPER_JAR" org.gradle.wrapper.GradleWrapperMain "$@"
fi

# Source-only distributions of StageGrid may omit the binary wrapper JAR.
# Bootstrap the exact pinned Gradle distribution from the official Gradle service.
GRADLE_VERSION=9.5.1
EXPECTED_SHA256=bafc141b619ad6350fd975fc903156dd5c151998cc8b058e8c1044ab5f7b031f
CACHE_DIR="${GRADLE_USER_HOME:-$HOME/.gradle}/stagegrid-bootstrap"
ZIP="$CACHE_DIR/gradle-$GRADLE_VERSION-bin.zip"
GRADLE_HOME="$CACHE_DIR/gradle-$GRADLE_VERSION"
mkdir -p "$CACHE_DIR"

if [ ! -x "$GRADLE_HOME/bin/gradle" ]; then
  if [ ! -f "$ZIP" ]; then
    echo "StageGrid: downloading Gradle $GRADLE_VERSION from services.gradle.org ..." >&2
    if command -v curl >/dev/null 2>&1; then
      curl -fL --retry 3 -o "$ZIP" "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" || { rm -f "$ZIP"; exit 1; }
    elif command -v wget >/dev/null 2>&1; then
      wget -O "$ZIP" "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" || { rm -f "$ZIP"; exit 1; }
    else
      echo "StageGrid: curl or wget is required for first-time Gradle bootstrap." >&2
      exit 1
    fi
  fi
  if command -v sha256sum >/dev/null 2>&1; then
    ACTUAL=$(sha256sum "$ZIP" | awk '{print $1}')
  elif command -v shasum >/dev/null 2>&1; then
    ACTUAL=$(shasum -a 256 "$ZIP" | awk '{print $1}')
  else
    echo "StageGrid: sha256sum or shasum is required to verify Gradle." >&2
    exit 1
  fi
  if [ "$ACTUAL" != "$EXPECTED_SHA256" ]; then
    echo "StageGrid: Gradle checksum mismatch; refusing to execute unverified download." >&2
    rm -f "$ZIP"
    exit 1
  fi
  command -v unzip >/dev/null 2>&1 || { echo "StageGrid: unzip is required." >&2; exit 1; }
  rm -rf "$GRADLE_HOME"
  unzip -q "$ZIP" -d "$CACHE_DIR"
fi

exec "$GRADLE_HOME/bin/gradle" "$@"
