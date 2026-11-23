# Android Slicer on CuraEngine — Architecture & Milestones

Nov 8, 2026 · @Nathanael Potoski

## Goals and scope

A native Android slicer built on CuraEngine that takes a Nomad Sculpt export to G-code and sends it to a Klipper printer with no PC and no flash drive. First target: Elegoo Neptune 4 Pro. The success test is simple: sculpt in Nomad, then slice and print from the tablet alone.

**In scope for version 1**

- Import STL and OBJ (what a sculpting app exports), then 3MF
- Place, rotate, and scale the model, and check it against the build volume
- Printer, material, and quality profiles, with a simple and a custom settings mode
- Slice on the device, preview the layers, save the G-code
- Upload to a Moonraker/Fluidd printer and start the print

**Out of scope for version 1**

- Multi-material, resin printers, cloud accounts, and a desktop release
- Remote monitoring beyond basic temperatures and progress

## Architecture overview

&#91;embedded content: architecture · 4 UI views, 6 core parts, 3 outside parts\]

The app is a thin Kotlin core between a touch UI and CuraEngine, which runs as its own process. The UI never calls the engine directly: it asks the core for a slice job, and the core writes the mesh and settings, launches the engine, and reads back the G-code. The same core uploads the finished file to the printer. The profile build script runs on a desktop before each release and is not part of the app.

## Key technical decisions

Eight choices shape the build. Rows 2, 3, and 7 are the riskiest; milestones M0, M2, and M7 exist to confirm them.

| Decision | Recommended choice | Why | Revisit if |
| --- | --- | --- | --- |
| UI toolkit | Kotlin with Jetpack Compose; Filament or OpenGL ES for the 3D viewport | Your choice: a native Android look and feel with no Qt licensing questions; a desktop build later would need a separate UI | The 3D viewport proves awkward to embed in Compose |
| Engine integration | Run CuraEngine as a separate process, packaged as a native library file | Newer Android versions block running binaries from app data, but files in the native library folder can run; a separate process also contains crashes | M0 shows it will not launch on Android 16 on the Galaxy Tab S8+ |
| Settings | Resolve Cura's definitions offline into flat JSON profiles; pass the values to the engine as settings | Cura's frontend, not the engine, works out derived settings, so doing it on the tablet means re-implementing that logic | The bundle becomes too large or too hard to keep current |
| Neptune 4 Pro G-code | Copy start/end G-code and limits from Elegoo's own OrcaSlicer/ElegooSlicer profile | The Klipper macros are Elegoo-specific; do not guess them | Elegoo changes its firmware |
| Mesh input | STL and OBJ first, then 3MF, with repair and decimation in a native library through the NDK | Sculpt exports can be dense and have holes | Real exports slice cleanly without help |
| Preview | Parse the generated G-code for the layer view | Shows exactly what will print, whatever the engine does | Large files make parsing too slow |
| Printer link | Moonraker HTTP API for upload, start, and status; bind traffic to the Wi-Fi network | A printer on a router with no internet can make Android send traffic over mobile data; plain HTTP to a local address also needs to be allowed | Elegoo changes the API |
| Files | Android's storage picker for import and export; app-private storage for profiles and projects | Fits Android's storage rules | n/a |

These points come from general knowledge of Android and CuraEngine, not a fresh check of current documentation.

## Milestones

Fifteen milestones (M0 to M14) in six phases, and each ends with a test you can run on your tablet. M0 gates everything else. Sizes are my rough guesses for one part-time developer: S is days, M is one to three weeks, L is a month or more.

### Phase A: Prove the engine runs on Android

| # | Deliverable | Exit test | Size |
| --- | --- | --- | --- |
| M0 | CuraEngine cross-compiled for arm64 with the NDK and launched from a bare test app on the Galaxy Tab S8+ (Android 16) | A test cube sliced on the tablet gives the same layer count and a near-identical print-time estimate as a desktop run with the same version and settings | M |
| M1 | Minimal app: pick an STL, slice with one hard-coded profile, save the G-code | A Nomad Sculpt export slices end to end on the tablet | S |
| M2 | Profile build script and profile loader: Neptune 4 Pro, PLA, three quality presets | Layer height, temperatures, speeds, and retraction in the G-code match the profile; a calibration cube prints well (by flash drive, for now) | M |

### Phase B: Viewport and model handling

| # | Deliverable | Exit test | Size |
| --- | --- | --- | --- |
| M3 | 3D viewport inside Compose (Filament or OpenGL ES): bed and build volume, orbit, pan, pinch-zoom, solid model rendering | A dense Nomad export stays responsive while you navigate | M |
| M4 | Move, rotate, scale, lay flat, drop to bed, multiple objects, undo and redo, out-of-bounds warning | Three objects arranged and sliced in one job | M |
| M5 | Mesh health: detect holes and non-manifold edges, repair or warn, decimate dense meshes with a native library, check units and scale | A deliberately broken mesh and a very dense sculpt both slice without crashing | M |
| M6 | G-code layer preview: layer slider, colours by move type, print time and filament estimates | The preview matches the sliced job, and the time estimate lands close to a real print | M |

### Phase C: Print without a PC

| # | Deliverable | Exit test | Size |
| --- | --- | --- | --- |
| M7 | Moonraker client: add a printer by IP, bind to the Wi-Fi network, upload, start the print, show temperatures and progress | Sculpt in Nomad, slice here, tap Print, and the printer starts with no flash drive and no PC (needs the travel-router link) | M |

### Phase D: Settings and profiles

| # | Deliverable | Exit test | Size |
| --- | --- | --- | --- |
| M8 | Settings UI with a simple mode (quality, infill, supports, adhesion) and a searchable custom mode, changed-value markers, and reset | Change five settings and see each one reflected in the G-code | L |
| M9 | Materials (PLA, PETG, TPU, ABS), editable start and end G-code, profile import and export | Duplicate a profile, edit it, export it, and re-import it on another device | M |
| M10 | Supports and per-object settings, with touch and S Pen painting of support blockers and enforcers | A painted blocker removes support from the marked area | L |

### Phase E: Features beyond the basics

| # | Deliverable | Exit test | Size |
| --- | --- | --- | --- |
| M11 | In-app calibration prints: temperature tower, retraction, flow, and pressure advance | Each one prints, and its result maps back to a profile value (first confirm the Klipper commands exist on Elegoo's firmware) | M |
| M12 | Engine-level extras only where app-side work cannot do it, such as seam control, kept as a small patch set on top of CuraEngine | Each patch has a test model and a documented diff against upstream | L |

### Phase F: Polish and release

| # | Deliverable | Exit test | Size |
| --- | --- | --- | --- |
| M13 | Tablet and phone layouts, 3MF project save and load, a background service so slicing survives screen-off, thermal and memory guards, accessibility | A long slice finishes with the screen locked, and the app is usable on a phone | L |
| M14 | Release: signed APK for sideloading, AGPL source offer and license screen, user docs, a beta on other printers | Testers on at least two other printer models slice and print without help | M |

## UI direction

Copy Cura's workflow, not its look or its code: a tool rail on the left, a viewport in the middle, a settings panel on the right, and one main action button. Give it an original name, icons, colours, and wording. Cura's UI code is LGPL-licensed and its name and artwork belong to Ultimaker, so write your own Compose UI, use an open icon set such as Material Symbols, and keep "Cura" and "Ultimaker" out of the app name. This is not legal advice.

- **Tablet, landscape:** tool rail on the left (move, rotate, scale, lay flat, supports), viewport in the centre, collapsible settings drawer on the right, and an action bar along the bottom that goes Slice, then progress, then Print or Save.
- **Phone, portrait:** viewport on top, settings in a bottom sheet, one floating action button.
- **Stages:** three tabs along the top: Prepare, Preview, and Printer.
- **Touch:** one finger orbits, two fingers pan and pinch-zoom, handles on screen instead of hover, tap targets of at least 48 dp, and S Pen support for painting supports.
- **Settings:** a simple mode by default (quality, infill, supports, adhesion), with a Custom toggle that opens the full searchable list.
- **An original touch:** a pre-slice check card that lists problems (outside the bed, thin features, overhangs) with a tap-to-fix action.

## Risks and open questions

The biggest risk is that the engine will not launch on Android; M0 tests that before anything else is built.

| Risk | Why it matters | Mitigation |
| --- | --- | --- |
| Engine will not launch as a packaged process | The whole plan depends on it | M0 tests it first; fallback is linking the engine as a library and running it in a worker thread |
| CuraEngine's dependencies are hard to cross-compile | Delays M0 | Pin one CuraEngine release, build dependencies one at a time, and record the recipe |
| Derived settings come out wrong | Bad G-code | Resolve profiles offline and compare against desktop Cura output |
| Large sculpt meshes | Memory use, slow slices, thermal throttling | Decimate, run slicing in a background service, show progress with cancel |
| Printer on a Wi-Fi network with no internet | Android may route traffic over mobile data | Bind to the Wi-Fi network and allow plain HTTP to local addresses |
| Elegoo's Klipper quirks | Start macros and limits differ from stock Klipper | Copy Elegoo's profile and test with real prints |
| Licensing | CuraEngine is AGPL-3.0; other libraries carry their own licenses | Publish your source under AGPL, check each library's license, and get legal review before wide distribution |
| Fork maintenance | CuraEngine keeps changing | Prefer app-side features; keep engine patches small and isolated |

**Open questions**

- Qt 6 or Kotlin with Compose for the UI? Kotlin with compose
- Which tablet and Android version is the first test device? Galaxy tab s8+ android 16
- Distribution: sideload, F-Droid, or Google Play? Sideloaded
- What should the app be called? It cannot be Cura-derived. MobileSlicer

The technical claims in this document come from general knowledge, not a fresh check of current CuraEngine or Android documentation.
