#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# Build CuraEngine and its dependencies from the sources fetched by
# fetch-sources.sh, for one target:
#
#   engine/build.sh host            desktop Linux build, the M0 reference
#   engine/build.sh android-arm64   arm64-v8a build with the Android NDK
#
# Environment:
#   WORK              build root (default ~/mobileslicer-build)
#   ANDROID_NDK_HOME  NDK path, required for android-arm64
#   ANDROID_API       minimum Android API level (default 29)
#   JOBS              parallel build jobs (default: CPU count)
#
# Output: $WORK/<target>/bin/CuraEngine
#
# CuraEngine is built without Arcus (the link to the desktop Cura UI) and
# without plugins; both pull in protobuf and gRPC, which the app does not need.
set -euo pipefail

TARGET="${1:?usage: build.sh host|android-arm64}"
HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=versions.env
source "$HERE/versions.env"
WORK="${WORK:-$HOME/mobileslicer-build}"
SRC="$WORK/src"
OUT="$WORK/$TARGET"
PREFIX="$OUT/prefix"
JOBS="${JOBS:-$(nproc)}"
mkdir -p "$OUT/build" "$PREFIX" "$OUT/bin"

COMMON=(-G Ninja -DCMAKE_BUILD_TYPE=Release
        -DCMAKE_INSTALL_PREFIX="$PREFIX" -DCMAKE_PREFIX_PATH="$PREFIX"
        -DBUILD_SHARED_LIBS=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON
        # CuraEngine needs C++20 (Conan normally sets this); use it everywhere.
        -DCMAKE_CXX_STANDARD=20 -DCMAKE_CXX_STANDARD_REQUIRED=ON)

case "$TARGET" in
  host)
    COMMON+=(-DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++)
    ;;
  android-arm64)
    : "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to the Android NDK path}"
    COMMON+=(-DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake"
             -DANDROID_ABI=arm64-v8a
             -DANDROID_PLATFORM="android-${ANDROID_API:-29}"
             # Shared C++ runtime: the engine and libtbb.so must use one copy of it.
             -DANDROID_STL=c++_shared
             # The NDK toolchain only searches its sysroot; let it see our prefix too.
             -DCMAKE_FIND_ROOT_PATH="$PREFIX"
             -DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=BOTH
             -DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=BOTH
             -DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=BOTH)
    ;;
  *) echo "unknown target: $TARGET" >&2; exit 1 ;;
esac

# cmake_dep <name> <source dir> [extra cmake args...]: configure, build, install.
cmake_dep() {
  local name="$1" src="$2"; shift 2
  if [ -f "$OUT/build/$name.done" ]; then echo "== $name (already built)"; return; fi
  echo "== $name"
  cmake -S "$src" -B "$OUT/build/$name" "${COMMON[@]}" "$@" > "$OUT/build/$name.configure.log"
  cmake --build "$OUT/build/$name" -j "$JOBS" > "$OUT/build/$name.build.log"
  cmake --install "$OUT/build/$name" > /dev/null
  touch "$OUT/build/$name.done"
}

cmake_dep fmt      "$SRC/fmt"      -DFMT_TEST=OFF -DFMT_DOC=OFF
cmake_dep spdlog   "$SRC/spdlog"   -DSPDLOG_FMT_EXTERNAL=ON -DSPDLOG_BUILD_EXAMPLE=OFF
cmake_dep range-v3 "$SRC/range-v3" -DRANGE_V3_TESTS=OFF -DRANGE_V3_EXAMPLES=OFF \
                                   -DRANGE_V3_PERF=OFF -DRANGE_V3_DOCS=OFF
cmake_dep zlib     "$SRC/zlib"     -DZLIB_BUILD_EXAMPLES=OFF
cmake_dep libpng   "$SRC/libpng"   -DPNG_SHARED=OFF -DPNG_STATIC=ON -DPNG_TESTS=OFF \
                                   -DPNG_TOOLS=OFF -DZLIB_ROOT="$PREFIX" -DZLIB_USE_STATIC_LIBS=ON
# oneTBB must be a shared library: linked statically, CuraEngine crashes at exit
# (TBB's globals are torn down before CuraEngine's). On Android it ships in the
# APK next to the engine as libtbb.so.
cmake_dep onetbb   "$SRC/onetbb"   -DTBB_TEST=OFF -DTBB_EXAMPLES=OFF -DTBB_STRICT=OFF \
                                   -DTBBMALLOC_BUILD=OFF -DBUILD_SHARED_LIBS=ON

# zlib always installs a shared library too; remove it so everything links statically.
rm -f "$PREFIX"/lib/libz.so*

ENGINE_HASH="$(git -C "$SRC/curaengine" rev-parse HEAD)"
cmake_dep curaengine "$SRC/curaengine" \
  -DCMAKE_PREFIX_PATH="$HERE/cmake/shims;$PREFIX" \
  -DMS_SRC="$SRC" -DMS_PREFIX="$PREFIX" \
  -DTBB_DIR="$HERE/cmake/shims" \
  -DENABLE_ARCUS=OFF -DENABLE_PLUGINS=OFF -DENABLE_TESTING=OFF -DENABLE_BENCHMARKS=OFF \
  -DENABLE_SENTRY=OFF -DEXTENSIVE_WARNINGS=OFF \
  -DZLIB_ROOT="$PREFIX" -DZLIB_USE_STATIC_LIBS=ON \
  -DCURA_ENGINE_VERSION="$CURAENGINE_REF" -DCURA_ENGINE_HASH="$ENGINE_HASH"

cp "$OUT/build/curaengine/CuraEngine" "$OUT/bin/CuraEngine"
cp -P "$PREFIX"/lib/libtbb.so* "$OUT/bin/"
if [ "$TARGET" = android-arm64 ]; then
  cp "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so "$OUT/bin/"
fi
echo "built $OUT/bin/CuraEngine"
file "$OUT/bin/CuraEngine" 2>/dev/null || true
