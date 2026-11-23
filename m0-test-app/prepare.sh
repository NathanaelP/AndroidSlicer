#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# Copy the arm64 engine build and the M0 test inputs into the app, ready for
# ./gradlew assembleRelease. Run engine/fetch-sources.sh and
# engine/build.sh android-arm64 first.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(dirname "$HERE")"
WORK="${WORK:-$HOME/mobileslicer-build}"
: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to the Android NDK path}"
STRIP="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
BIN="$WORK/android-arm64/bin"

LIBS="$HERE/app/src/main/jniLibs/arm64-v8a"
ASSETS="$HERE/app/src/main/assets"
rm -rf "$LIBS" "$ASSETS"
mkdir -p "$LIBS" "$ASSETS"

# The engine is an executable, but Android only installs files named lib*.so
# from the APK into the native library folder, the one place apps may run
# binaries from. Hence the rename.
"$STRIP" -o "$LIBS/libcuraengine.so" "$BIN/CuraEngine"
"$STRIP" -o "$LIBS/libtbb.so" "$BIN/libtbb.so"
cp "$BIN/libc++_shared.so" "$LIBS/"

cp "$WORK/src/cura/resources/definitions/fdmprinter.def.json" \
   "$WORK/src/cura/resources/definitions/fdmextruder.def.json" \
   "$REPO/engine/test/cube20.stl" \
   "$REPO/engine/test/m0-settings.txt" \
   "$REPO/engine/test/m0-expected.txt" \
   "$ASSETS/"

ls -la "$LIBS" "$ASSETS"
