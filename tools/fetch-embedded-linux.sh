#!/usr/bin/env bash
set -euo pipefail
# Pinned source revision of the PRoot engine; no Termux runtime or app needed.
REV=85764d46f9c36b2ddd5bc6f1cdb45b4bcf4f851d
BASE="https://raw.githubusercontent.com/oonid/pr/${REV}/android/proot-engine/src/main/jniLibs/arm64-v8a"
LIBS=app/src/main/jniLibs/arm64-v8a
mkdir -p "$LIBS" app/src/main/assets
for binary in libproot.so libproot-loader.so; do
  curl --fail --location --silent --show-error --retry 3 --max-time 120 \
    "$BASE/$binary" -o "$LIBS/$binary"
  test -s "$LIBS/$binary"
done
file "$LIBS"/*.so
# Alpine ARM64 official minirootfs, released October 21, 2026.
ALPINE=alpine-minirootfs-3.23.7-aarch64.tar.gz
MIRROR="https://dl-cdn.alpinelinux.org/alpine/v3.23/releases/aarch64"
curl --fail --location --silent --show-error --retry 3 "$MIRROR/$ALPINE" -o "app/src/main/assets/$ALPINE"
curl --fail --location --silent --show-error --retry 3 "$MIRROR/$ALPINE.sha256" -o "/tmp/$ALPINE.sha256"
( cd app/src/main/assets && cp "/tmp/$ALPINE.sha256" "$ALPINE.sha256" && sha256sum -c "$ALPINE.sha256" && mv "$ALPINE" alpine-minirootfs.tar.gz )
rm "app/src/main/assets/$ALPINE.sha256"