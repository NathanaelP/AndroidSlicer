# MobileSlicer Android app

The app itself: Kotlin and Jetpack Compose, with CuraEngine running as a separate process.

## Modules

| Module | What it does |
| --- | --- |
| `engine` | The core, with no UI. `MeshImporter` turns STL (ASCII or binary) and OBJ into a clean binary STL. `EngineRunner` runs CuraEngine and reports progress. `Profile` loads the JSON profiles built by [`profiles/build_profiles.py`](../profiles/README.md). It also holds the engine binaries and Cura's setting definitions, which `prepare-engine.sh` copies in. |
| `app` | The Compose UI: open a model, place it, slice it, save the G-code. `viewport/` holds the 3D view (M3). |

## M1 scope

One screen:

1. **Open model:** any STL or OBJ, through Android's file picker. It's converted on import, so a Nomad Sculpt OBJ with vertex colours works. The screen shows the triangle count and the size as exported.
2. **Placement:** scale, height in millimetres, or **Fit to bed** and **Stand upright** for models exported with Y up. The engine centres the model on the bed and drops it to the plate. The size is checked against the profile's build volume (235 × 230 × 270 mm).
3. **Slice:** with one of the Fine, Normal or Fast PLA presets (M2), built from Cura's Neptune 4 Pro profile. The screen shows progress through the engine's six stages, and you can cancel.
4. **Save G-code:** anywhere, through the file picker. The screen shows print time, layers and filament.

After slicing, `GcodePostProcessor` writes the real print time, filament length and model bounds into the G-code header (the engine leaves placeholders in command-line mode, and printer software reads them). It also checks that no nozzle temperature mid-print falls more than 30 °C below the print temperature. If one does, the app refuses to save the file.

Since M2 the profiles come from Cura's own Elegoo Neptune 4 Pro definitions, with Elegoo's start and end G-code. M1 used a placeholder profile.

## 3D viewport (M3)

An OpenGL ES 3.0 view of the bed (235 × 230 mm, 10 mm grid with heavier lines every 50 mm), the build-volume outline and the model.

- **Gestures:** one finger orbits, two fingers pan and pinch to zoom, and a double tap or **Fit view** frames the model. Each newly opened model is framed automatically. The camera stays at least 1 mm above the bed: the bed is opaque, so a view from underneath shows nothing.
- **Placement:** the model is drawn exactly where CuraEngine will put it, using `modelMatrix()`. That is the same scale and stand-upright transform the engine receives, then centred on the bed and resting on it. It turns red when it doesn't fit the build volume.
- **The bed** sits 0.2 mm below Z = 0 (`BED_Z`), so a model resting on Z = 0 never fights it for depth. An earlier version pushed the bed back with a polygon offset instead, which let the model's contact patches show through the bed from below.
- **Rendering:**
  - The model is uploaded once, streamed in chunks from the importer's STL. Only positions are stored (36 bytes per triangle, about 72 MB for 2 million triangles).
  - The fragment shader computes flat-shading normals from screen-space derivatives.
  - Lighting is a key light from the upper left plus a headlight. It's two-sided, so meshes with flipped faces still read well.
  - The view draws only when something changes, with 4× multisampling where the device offers it.
- **Layout:** on screens at least 840 dp wide (a tablet in landscape) the viewport fills the left and the controls sit in a panel on the right. On a phone the viewport is on top, with the controls below.
- **Exit-test readout:** the corner shows the triangle count, the frame rate while the view is moving, and how long the model took to load.

Desktop checks, since a device isn't available in the build environment:
- `tools/check_shaders.py` compiles the shaders embedded in the Kotlin source with Khronos `glslangValidator`.
- `ViewportScenesTest` writes the matrices the app computes for a few scenes. `tools/render_preview.py` then draws them with the same shaders in WebGL2, through headless Chromium on SwiftShader. Run the unit tests first, then: `NODE_PATH=$(npm root -g) python3 android/tools/render_preview.py OUT_DIR`.

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
| G-code post-processor unit tests (header fill-in, unsafe temperature detection) | 3 of 3 pass |
| Test cube at 15%, 50%, 100% and 300% scale after the `cool_min_temperature` fix | Nozzle 215 °C on layer 0, 210 °C after, off only at the end, at every size |

## Found on the first device test

The first Nomad export (about 3 mm tall, since Nomad doesn't export in millimetres) produced G-code that switched the nozzle heater off on layer 0. The cause was `cool_min_temperature`: when a layer prints faster than the minimum layer time, the engine lowers the nozzle temperature towards this value. Cura's formula sets it to the print temperature, but the definitions' fallback is 0 °C. Small models have only short layers, so the nozzle was turned down towards 0. The profile now sets it, and the temperature check above catches this kind of fault if another setting causes it. Resolving settings properly is M2's job.

## Device results

| Date | Device | Model | Result |
| --- | --- | --- | --- |
| 2026-11-24 | Samsung Galaxy S25 Ultra, Android 17 | Nomad Sculpt export ("Senhor Leite"), scaled to 28.3 mm tall | **M1 exit test passed.** Sliced end to end on the device in 2.4 s: 142 layers, 14 min 9 s, 0.69 m of filament. The G-code was checked: header filled in, safe temperatures throughout, centred on the bed, extrusion on every layer. |

| 2026-11-25 | Samsung Galaxy S25 Ultra, Android 17 | Nomad Sculpt teddy bear, 4,498,610 triangles, scaled to 64.6 mm | **M3 exit test passed.** Loaded in 0.4 s and stayed at 98–114 fps while orbiting, panning and zooming. Two fixes came out of the test: the model's base showed through the bed when seen from below, and **Fit to bed** was squeezed on a phone. |
