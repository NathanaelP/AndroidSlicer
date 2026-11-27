#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# Build libmeshsimplify.so (engine/jni/meshsimplify.cpp plus meshoptimizer's
# simplifier) for one target:
#
#   engine/build-simplifier.sh host            for the desktop unit tests
#   engine/build-simplifier.sh android-arm64   for the app (prepare-engine.sh copies it)
#   engine/build-simplifier.sh cli             a desktop command-line tool, for timing
#
# Needs meshoptimizer from fetch-sources.sh. Output: $WORK/<target>/bin/libmeshsimplify.so
set -euo pipefail

TARGET="${1:?usage: build-simplifier.sh host|android-arm64|cli}"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${WORK:-$HOME/mobileslicer-build}"
MO="$WORK/src/meshoptimizer/src"
OUT="$WORK/${TARGET/cli/host}/bin"
mkdir -p "$OUT"
SRCS=("$HERE/jni/meshsimplify.cpp" "$MO/simplifier.cpp" "$MO/allocator.cpp")
FLAGS=(-O2 -std=c++17 -fPIC -shared -fvisibility=hidden -I"$MO" -o "$OUT/libmeshsimplify.so")

case "$TARGET" in
  host)
    JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
    c++ "${FLAGS[@]}" -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" "${SRCS[@]}"
    ;;
  cli)
    # A desktop command-line tool (no JNI use) for measuring time and memory.
    JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
    c++ -O2 -std=c++17 -DMESHSIMPLIFY_CLI -I"$MO" -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
      -o "$OUT/meshsimplify" "${SRCS[@]}"
    ls -la "$OUT/meshsimplify"; exit 0
    ;;
  android-arm64)
    : "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to the Android NDK path}"
    API="${ANDROID_API:-29}"
    CXX="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android$API-clang++"
    # The C++ runtime linked in statically: the library only needs operator new/delete.
    # 16 KB page alignment, as recent Android versions require.
    "$CXX" "${FLAGS[@]}" -static-libstdc++ -Wl,-z,max-page-size=16384 "${SRCS[@]}"
    ;;
  *) echo "unknown target $TARGET" >&2; exit 1 ;;
esac
ls -la "$OUT/libmeshsimplify.so"
