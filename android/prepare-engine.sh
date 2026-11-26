#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# Copy the arm64 engine build and Cura's setting definitions into the :engine
# module, ready for ./gradlew assembleRelease. Run engine/fetch-sources.sh,
# engine/build.sh android-arm64 and engine/build-simplifier.sh android-arm64
# first (see engine/README.md).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${WORK:-$HOME/mobileslicer-build}"
: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to the Android NDK path}"
STRIP="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
BIN="$WORK/android-arm64/bin"
LIBS="$HERE/engine/src/main/jniLibs/arm64-v8a"
DEFS="$HERE/engine/src/main/assets/definitions"

rm -rf "$LIBS" "$DEFS"
mkdir -p "$LIBS" "$DEFS"
# Android only installs files named lib*.so from the APK into the native library
# folder, the one place apps may run binaries from; hence the engine's new name.
"$STRIP" -o "$LIBS/libcuraengine.so" "$BIN/CuraEngine"
"$STRIP" -o "$LIBS/libtbb.so" "$BIN/libtbb.so"
# The mesh simplifier (engine/build-simplifier.sh android-arm64), loaded through JNI.
"$STRIP" -o "$LIBS/libmeshsimplify.so" "$BIN/libmeshsimplify.so"
cp "$BIN/libc++_shared.so" "$LIBS/"
cp "$WORK/src/cura/resources/definitions/fdmprinter.def.json" \
   "$WORK/src/cura/resources/definitions/fdmextruder.def.json" "$DEFS/"
ls -la "$LIBS" "$DEFS" | grep -v '^total'
