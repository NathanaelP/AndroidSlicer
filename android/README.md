# MobileSlicer Android app

The app itself: Kotlin and Jetpack Compose, with CuraEngine running as a separate process.

## Modules

| Module | What it does |
| --- | --- |
| `engine` | The core, with no UI. `MeshImporter` turns STL (ASCII or binary) and OBJ into a clean binary STL. `EngineRunner` runs CuraEngine and reports progress. `Profile` loads the JSON profiles built by [`profiles/build_profiles.py`](../profiles/README.md). It also holds the engine binaries and Cura's setting definitions, which `prepare-engine.sh` copies in. |
| `app` | The Compose UI: open models, place them, slice, save the G-code. `viewport/` holds the 3D view (M3); `scene/` holds the objects, arranging, undo and picking (M4). |

## M1 scope

One screen:

1. **Open model:** any STL or OBJ, through Android's file picker. It's converted on import, so a Nomad Sculpt OBJ with vertex colours works. The screen shows the triangle count and the size as exported.
2. **Placement:** scale, height in millimetres, or **Fit to bed** and **Stand upright** for models exported with Y up. The engine centres the model on the bed and drops it to the plate. The size is checked against the profile's build volume (235 × 230 × 270 mm).
3. **Slice:** with one of the Fine, Normal or Fast PLA presets (M2), built from Cura's Neptune 4 Pro profile. The screen shows progress through the engine's six stages, and you can cancel.
4. **Save G-code:** anywhere, through the file picker. The screen shows print time, layers and filament.

After slicing, `GcodePostProcessor` writes the real print time, filament length and model bounds into the G-code header (the engine leaves placeholders in command-line mode, and printer software reads them). It also checks that no nozzle temperature mid-print falls more than 30 °C below the print temperature. If one does, the app refuses to save the file.

Since M2 the profiles come from Cura's own Elegoo Neptune 4 Pro definitions, with Elegoo's start and end G-code. M1 used a placeholder profile.

## Mesh health (M5): floating parts and splitting

- **Parts on import:** `MeshParts` finds a mesh's connected parts, meaning triangles joined through shared corners (corners are matched by exact coordinates, which is how exporters write shared vertices). It then rewrites the mesh with each part's triangles together, largest part first, recording each part's exact bounds and its own sample points.
  - A single-part mesh, the usual case, is just renamed with no rewrite.
  - A "triangle soup" with more than 10,000 parts is treated as one part.
  - On a desktop, importing 2 million triangles takes about 2–3 s in all.
  - Sample points now also cover the surface of large triangles, spaced 1/100 of the model's size, so flat faces of simple models count too.
- **Floating parts:** a part floats when, after the object's rotation and scale, its lowest point is more than 0.3 mm above the bed, and no part that reaches lower has surface inside its box. So an eye pushed into a head, a hat sitting on one, or an arm sticking out of a body counts as attached, while a loose piece above the model doesn't. This works from sample points, so it's an estimate.
- **Shown:** floating parts are drawn in purple. The objects list, the selected object's card and the slice card all say how high they start. Slicing is still allowed, since supports (M8/M10) are what make such parts printable.
- **Split into parts:** the selected object's card has a **Split into parts** button when its mesh has more than one part. Parts whose surfaces come within 0.3 mm of each other (at the object's scale) stay together, so an articulated figure stays one object while a loose figure becomes its own. `MeshParts.touchingGroups` buckets the sample points on a grid and joins parts with points that close, so the real reach is 0.3 mm plus about 1% of the model's size. `MeshParts.extract` copies each group's triangle ranges and sample points into a mesh file of its own.
  - Each new object keeps the scale and rotation and stays where it was over the bed, then rests on the bed like any object. A floating piece drops down. If pieces end up overlapping, the app says so and **Arrange** spaces them out.
  - Splitting is one undo step. When every part touches another, the app says the object stays as it is.

## Mesh health (M5): holes and broken edges

- **Edge check on import:** while finding parts, the importer has every triangle's welded corner ids. It sorts the triangles' edges (24 bytes per triangle, freed afterwards) and counts how each edge is used. A clean, closed part has every edge shared by exactly two triangles running it opposite ways. Per part, the check counts:
  - **open edges** (one triangle), traced into **hole** outlines where they close into rims;
  - **non-manifold edges** (three or more triangles: doubled or overlapping faces);
  - **flipped edges** (two triangles running the same way: a face inside out);
  - **flat triangles** (two corners in one place).
  - It's skipped above 8 million triangles. Rims aren't traced beyond 200,000 open edges, or for parts of fewer than 4 triangles. A mesh averaging under 4 triangles per part is treated as a triangle soup: one part, not separate objects.
  - Importing 2 million triangles still takes about 1–1.4 s on a desktop with the check included.
- **Shown:** hole rims are drawn as green lines over everything, so holes on the far side show too. The selected object's card has a **Mesh check** section and the objects list a short note. A clean mesh says "closed, no problems found".
- **Fill holes** (`MeshRepair.fillHoles`) closes every traced hole with a fan of triangles from the middle of its rim, running the opposite way to the triangles beside it so it faces outwards. It then checks the mesh again, and is one undo step. It suits the flat, roughly round openings exporters leave; an odd-shaped hole is closed, though not neatly.
- **Leftover open edges:** an object with open edges gets CuraEngine's `meshfix_extensive_stitching` for that object only. Non-manifold and flipped edges are reported but left to the engine, which slices them fine (see below).
- **Nothing to print:** when the engine finds no closed outline in any layer, the app says so and points to Fill holes, instead of offering empty G-code.

### Exit test, broken half (desktop)

`tools/broken_meshes.py` writes seven broken meshes plus a clean control: two holes cut in a sphere, a box with a missing wall, 10% of faces flipped, every face doubled plus two cubes touching along an edge, flat and non-finite triangles, a triangle soup with no shared corners, and all of these at once. `BrokenMeshesTest` imports them as the app does and fills their holes. `tools/slice_broken.sh` slices every result with the desktop engine and the Normal profile.

```
python3 tools/broken_meshes.py engine/build/broken
./gradlew :engine:testDebugUnitTest --tests '*BrokenMeshesTest*'
tools/slice_broken.sh
```

All of them slice without crashing. The only empty result is the box with a missing wall: no layer has a closed outline, even with stitching. After Fill holes it slices to 100 layers. In the app, that case now gives the message above. Filling the cut sphere's holes gives it a closed top and side again (81,698 extrusion moves, against 69,386 open).

## Several objects (M4)

- **Objects:**
  - **Add model** imports another STL or OBJ. It goes in the middle of the bed, or everything is re-arranged if the middle is taken.
  - Tap an object (in the view or the list) to select it. Drag it in the view to move it: it follows your finger across the bed.
  - **Arrange** lays everything out in rows, largest first. Objects are spaced for their brims (2 × `brim_width` + 4 mm) and the whole layout is centred.
- **Selected object:**
  - Scale or height, and **Fit to bed**.
  - Rotate 90° around X, Y or Z, or 45° around Z. X 90° stands up a model exported with Y up.
  - **Lay flat…**, then tap the face that should rest on the bed.
  - Reset rotation, **Duplicate** and **Delete**.
- **Always on the bed:** objects are dropped onto the bed automatically, like Cura's "drop down to build plate".
- **Warnings:**
  - Objects outside the build volume turn red, and slicing waits until they're back.
  - Overlapping objects get a warning, since they'd print fused together.
- **Undo/Redo:** in the top bar. A drag, or typing into one field, is one step.
- **Slicing:** all objects go to the engine together. Each gets its own `mesh_rotation_matrix` (rotation and scale) and is loaded with `-l`. It's then centred, dropped and moved with `center_object` and `mesh_position_x/y` (see `EngineRunner.buildCommand`).
- **Bounds after rotation:**
  - Exact for 90° turns, from the import bounds.
  - For other angles the importer keeps a sample of the mesh's vertices: one per cell of a grid 1/400 of the model's size (about 200,000 points for a 2-million-triangle sculpt, adding about 0.7 s to the import on a desktop).
  - The engine always drops objects onto the bed exactly; the samples only affect what the app draws.
- **Lay flat:** reads the object's mesh file and finds the tapped face with a ray-triangle test, then rotates that face to point straight down.

Desktop check: `tools/check_placement.py PROFILE OUT_DIR` slices the three-object layout written by `ViewportScenesTest` and checks each object's extent in the G-code against where the app draws it. Run the unit tests first.

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
| 2026-11-25 | Samsung Galaxy Tab S8+, landscape | Same 4.5-million-triangle teddy bear, version 0.3.1 | Sits flat on the bed with the fixes in place. About 55 fps while moving, and still smooth to use. First run of the tablet layout, with the viewport beside the settings panel. |
| 2026-11-25 | Samsung Galaxy Tab S8+, landscape | Three Nomad Sculpt exports (2,057,182 triangles in total): one laid flat, one rotated, arranged | **M4 exit test passed.** Three objects sliced together on the tablet in 7.7 s: 532 layers, 3 h 21 min, 30.35 m of PLA. The viewport stayed at 121 fps. The G-code passes all 21 checks against the Normal profile. **Found:** one export held a second, tiny figure far from the main one, a leftover object in the Nomad scene. It would print in mid-air, starting 74 mm above the bed. Detecting floating parts is planned for M5 (mesh health). |
| 2026-11-25 | Samsung Galaxy Tab S8+, landscape | Version 0.5.0: "Jesus and Us" (two figures made of many parts, one figure loose) plus a carabiner and a jug, 7,454,422 triangles in all | **Floating parts passed.** Lying down, the loose figure and parts of the large figure that lift off the bed (such as the torso, 0.32 mm up) showed in purple: "8 separate parts start 0.32–113.2 mm above the bed". Stood up with X 90°, the loose figure reached the bed and only 2 parts were left, starting 51.5 mm up. About 36 fps with 7.4 million triangles. The G-code (861 layers, 1 h 56 min, 8.96 m) passes all 21 checks against the Normal profile. |
