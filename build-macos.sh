#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_DIR="$SCRIPT_DIR/app"

if ! command -v java >/dev/null 2>&1; then
  printf '%s\n' 'ERROR: Java 21 is required. Install Temurin/OpenJDK 21 and retry.' >&2
  exit 20
fi

JAVA_VERSION="$(java -version 2>&1 | head -n 1)"
if [[ ! "$JAVA_VERSION" =~ version\ \"21([.\"]|[[:space:]]) ]]; then
  printf 'ERROR: Java 21 is required; detected: %s\n' "$JAVA_VERSION" >&2
  exit 21
fi

chmod +x "$APP_DIR/gradlew"
cd "$APP_DIR"
./gradlew --no-daemon clean test build cyclonedxDirectBom
printf '%s\n' 'BUILD_OK nativeTests=false'
