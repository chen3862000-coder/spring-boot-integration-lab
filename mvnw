#!/bin/sh
set -eu

BASE_DIR=$(cd "$(dirname "$0")" && pwd)
WRAPPER_PROPS="$BASE_DIR/.mvn/wrapper/maven-wrapper.properties"
DIST_URL=$(awk -F= '/^distributionUrl=/{print $2}' "$WRAPPER_PROPS")
DIST_DIR="$BASE_DIR/.mvn/wrapper/dists"
MAVEN_HOME="$DIST_DIR/apache-maven-3.9.11"
ARCHIVE="$DIST_DIR/apache-maven.zip"

if [ ! -x "$MAVEN_HOME/bin/mvn" ]; then
  mkdir -p "$DIST_DIR"
  if [ ! -f "$ARCHIVE" ]; then
    if command -v curl >/dev/null 2>&1; then
      curl -fsSL "$DIST_URL" -o "$ARCHIVE"
    elif command -v wget >/dev/null 2>&1; then
      wget -q "$DIST_URL" -O "$ARCHIVE"
    else
      echo "curl or wget is required to download Maven" >&2
      exit 1
    fi
  fi
  if command -v unzip >/dev/null 2>&1; then
    unzip -q "$ARCHIVE" -d "$DIST_DIR"
  else
    (cd "$DIST_DIR" && jar xf "$ARCHIVE")
  fi
fi

exec "$MAVEN_HOME/bin/mvn" "$@"
