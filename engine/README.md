# Engine build (milestone M0)

This folder builds [CuraEngine](https://github.com/Ultimaker/CuraEngine) 5.13.0 for arm64 Android with the NDK, plus a desktop Linux build to compare it against. It's the recorded recipe the plan's risk table asks for.

CuraEngine normally builds with Conan and Ultimaker's package server. This recipe uses plain CMake instead: every dependency is a pinned git tag or checksummed tarball, and the small Conan-generated CMake files CuraEngine expects are replaced by stand-ins in [`cmake/shims/`](cmake/shims/).

## Quick start

On a Linux machine with git, CMake 3.20+, Ninja and clang:

```sh
engine/fetch-sources.sh                    # ~430 MB into ~/mobileslicer-build/src
engine/build.sh host                       # desktop reference build (~3 min on 4 cores)
ANDROID_NDK_HOME=/path/to/android-ndk-r29 \
  engine/build.sh android-arm64            # arm64-v8a build
```

Results go to `~/mobileslicer-build/<target>/bin/`. Set `WORK` to build somewhere else.

To slice the test cube with the desktop build:

```sh
engine/test/slice-cube.sh ~/mobileslicer-build/host/bin/CuraEngine \
  ~/mobileslicer-build/src/cura/resources/definitions out.gcode
```

## Files

| File | Purpose |
| --- | --- |
| `versions.env` | Every source and its pinned tag, commit or checksum |
| `fetch-sources.sh` | Fetches all sources at those versions |
| `build.sh` | Builds the dependencies, then CuraEngine, for `host` or `android-arm64` |
| `cmake/shims/` | Stand-ins for the CMake package files Conan would generate |
| `test/cube20.stl` | 20 mm test cube |
| `test/m0-settings.txt` | Settings for the M0 test slice (rough Neptune 4 Pro values) |
| `test/slice-cube.sh` | Slices the cube and prints layers, time, filament and a toolpath hash |
| `test/m0-expected.txt` | Desktop reference results the tablet must match |

## Build choices and findings

- **Arcus and plugins are off.** Arcus is the link to the desktop Cura UI and plugins use gRPC; both need protobuf and gRPC, which are hard to cross-compile and which the app doesn't need.
- **C++20 throughout.** CuraEngine needs it; Conan normally sets it.
- **oneTBB is a shared library.** Linked statically, CuraEngine crashes at exit (TBB's globals are destroyed before CuraEngine's), which oneTBB's own build warns about. On Android it ships as `libtbb.so`.
- **Android uses the shared C++ runtime** (`libc++_shared.so`), since the engine and `libtbb.so` must share one copy. Everything else is linked statically.
- **The Android engine needs only `libtbb.so` and `libc++_shared.so`** beyond Android's system libraries. Stripped, the three files total about 13 MB.
- **Settings:** without the Cura frontend, the engine skips parent settings and formula-only settings. `slice-cube.sh` passes `--force-read-parent --force-read-nondefault` to load them. It also applies the test settings to extruder 0, since the extruder's own defaults would otherwise override them (for example 2.85 mm filament instead of 1.75 mm). This is a stopgap; resolving profiles properly is M2.
- **Print time and filament** are not written into the G-code header in command-line mode: desktop Cura fills in those placeholders itself. The engine logs them, and `slice-cube.sh` reads them from the log.

## Desktop reference result

CuraEngine 5.13.0, clang 18 on x86_64 Linux, three identical runs:

| Layers | Print time | Filament | Toolpath SHA-256 |
| --- | --- | --- | --- |
| 100 | 1602 s (26 min 42 s) | 4724 mm³ | `b3d1cb4d…85527eda87d4` |
