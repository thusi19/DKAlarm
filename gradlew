#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
VER=8.11.1
BASE="$ROOT/.gradle-bootstrap"
DIST="$BASE/gradle-$VER"
ZIP="$BASE/gradle-$VER-bin.zip"
if [ ! -x "$DIST/bin/gradle" ]; then
  mkdir -p "$BASE"
  URL="https://services.gradle.org/distributions/gradle-$VER-bin.zip"
  if command -v curl >/dev/null 2>&1; then curl -L --fail "$URL" -o "$ZIP"
  elif command -v wget >/dev/null 2>&1; then wget -O "$ZIP" "$URL"
  else echo "Need curl or wget to download Gradle $VER" >&2; exit 1; fi
  if command -v unzip >/dev/null 2>&1; then unzip -q -o "$ZIP" -d "$BASE"
  else echo "Need unzip to extract Gradle $VER" >&2; exit 1; fi
fi
exec "$DIST/bin/gradle" "$@"
