#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

TARGET_VERSION="${1:-}"
VERSION_FILE="build.gradle.kts"

[[ "$TARGET_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "Invalid version: $TARGET_VERSION"
[ -n "${VERSION_BUMP_FILE_LIST:-}" ] || fail "VERSION_BUMP_FILE_LIST is required."
[ -f "$VERSION_FILE" ] || fail "Version file not found: $VERSION_FILE"

VERSION_LINE_COUNT=$(grep -Ec '^version = "[^"]+"$' "$VERSION_FILE" || true)
[ "$VERSION_LINE_COUNT" -eq 1 ] || fail "Expected exactly one Gradle version assignment in $VERSION_FILE."

TMP_FILE="${TMPDIR:-/tmp}/java-sftp-pass-wrapper-version-bump-$$.tmp"
trap 'rm -f "$TMP_FILE"' EXIT

awk -v target_version="$TARGET_VERSION" '
  BEGIN { replaced = 0 }
  /^version = "[^"]+"$/ {
    print "version = \"" target_version "\""
    replaced++
    next
  }
  { print }
  END {
    if (replaced != 1) {
      exit 42
    }
  }
' "$VERSION_FILE" > "$TMP_FILE" || fail "Failed to update Gradle version in $VERSION_FILE."

mv "$TMP_FILE" "$VERSION_FILE"

printf '%s\n' "$VERSION_FILE" > "$VERSION_BUMP_FILE_LIST"
git add "$VERSION_FILE"
