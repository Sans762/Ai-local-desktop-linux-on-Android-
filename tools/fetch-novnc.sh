#!/usr/bin/env bash
set -euo pipefail
# Pinned noVNC v1.7.0 commit, MPL-2.0. Serve locally inside Android's WebView.
REV=63107bd06d9e1f6136ff21aeda8cd62cbf0d433e
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
curl --fail --location --silent --show-error --retry 3 --max-time 120 \
  "https://codeload.github.com/novnc/noVNC/tar.gz/$REV" -o "$TMP/novnc.tar.gz"
mkdir -p "$TMP/extracted" app/src/main/assets/novnc
tar -xzf "$TMP/novnc.tar.gz" -C "$TMP/extracted" --strip-components=1
cp -R "$TMP/extracted/core" "$TMP/extracted/vendor" app/src/main/assets/novnc/
cp "$TMP/extracted/LICENSE.txt" app/src/main/assets/novnc/LICENSE.txt
test -s app/src/main/assets/novnc/core/rfb.js
test -s app/src/main/assets/novnc/vendor/pako/lib/zlib/inflate.js
echo "Embedded noVNC source pinned to $REV (see assets/novnc/LICENSE.txt)"
