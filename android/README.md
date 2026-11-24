# MobileSlicer Android app

The app itself: Kotlin and Jetpack Compose, with CuraEngine running as a separate process.

## Modules

| Module | What it does |
| --- | --- |
| `engine` | The core, with no UI. `MeshImporter` turns STL (ASCII or binary) and OBJ into a clean binary STL. `EngineRunner` runs CuraEngine and reports progress. `Profile` loads a settings file. It also holds the engine binaries and Cura's setting definitions, which `prepare-engine.sh` copies in. |
| `app` | The Compose UI: open a model, place it, slice it, save the G-code. |

## M1 scope

One screen:

1. **Open model:** any STL or OBJ, through Android's file picker. It's converted on import, so a Nomad Sculpt OBJ with vertex colours works. The screen shows the triangle count and the size as exported.
2. **Placement:** scale (with **Fit to bed**) and **Stand upright** for models exported with Y up. The engine centres the model on the bed and drops it to the plate. The size is checked against the 225 × 225 × 265 mm build volume.
3. **Slice:** with the built-in profile (`engine/src/main/assets/profiles/neptune4pro-pla-m1.txt`). The screen shows progress through the engine's six stages, and you can cancel.
4. **Save G-code:** anywhere, through the file picker. The screen shows print time, layers and filament.

The profile is a **placeholder** until M2. Its start and end G-code are generic, not Elegoo's, so check them before printing.

## Building

Build the engine first (see [`engine/README.md`](../engine/README.md)), then:

```sh
export ANDROID_NDK_HOME=/path/to/android-ndk-r29
android/prepare-engine.sh                 # copies the engine and definitions in
echo "sdk.dir=/path/to/android/sdk" > android/local.properties
cd android
./gradlew :engine:testDebugUnitTest       # importer tests, on the desktop JVM
./gradlew :app:assembleRelease            # app/build/outputs/apk/release/app-release.apk
```

Release builds are shrunk with R8 and signed with the local debug key, for sideloading.

To time the importer on a large file: `MOBILESLICER_LARGE_MESH=/path/to/mesh.obj ./gradlew :engine:testDebugUnitTest --tests '*LargeFile*' -i`.

## Desktop checks done for M1

| Check | Result |
| --- | --- |
| Importer unit tests (OBJ with colours, exponents, quads, negative indices and CRLF; ASCII and binary STL; bad input) | 8 of 8 pass |
| OBJ cube converted by the importer vs the original STL cube | Identical G-code toolpaths |
| Stand upright and scale on a 10 × 40 × 10 mm box | 50 layers as is, 200 upright, 100 upright at 50% |
| 2-million-triangle sphere OBJ with vertex colours (74 MB) | Import 1.2 s; slice 3.5 s; engine peak memory 417 MB (desktop) |
