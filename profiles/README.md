# Printer profiles (milestone M2)

MobileSlicer's profiles are built on a desktop, from Cura's own definitions, before each release. The app ships the results as flat JSON files in `android/engine/src/main/assets/profiles/`.

## Why

Desktop Cura works out most settings from formulas in its frontend before calling CuraEngine (for example `speed_wall = speed_print / 2`). Without that frontend, the engine only knows each setting's fallback default. Those defaults are often wrong and sometimes dangerous: in M1, `cool_min_temperature` fell back to 0 °C and switched the nozzle off on small prints. `build_profiles.py` does the frontend's work offline, so the app passes the engine a complete, resolved set of 736 settings.

## How it works

1. **Stack the settings layers in Cura's order**, lowest first:
   - definitions: `fdmprinter`, Elegoo base, Neptune 4, Neptune 4 Pro;
   - extruder: `fdmextruder`, Elegoo extruder 0;
   - the 0.4 mm nozzle variant;
   - Generic PLA 1.75 mm (from Ultimaker's `fdm_materials`);
   - the global quality preset;
   - the PLA-specific quality settings.

   Definitions are merged property by property, as Cura does.
2. **Evaluate every formula** with a small, restricted evaluator. It interprets only the subset of Python that Cura's 517 formulas use: arithmetic, comparisons, conditionals, a few built-ins, `math.*` and Cura's `extruderValue(s)`/`resolveOrValue`. It never calls `eval()`.
3. **Apply Cura's pre-slice steps** (`StartSliceJob`):
   - Fill in `{placeholders}` in the start and end G-code, including `{if}`/`{else}`.
   - Turn off the engine's own heating commands when the start G-code already heats the nozzle or bed.

## Presets

| Preset | Layer height | First layer | Top/bottom layers | Print speed | Outer wall | Nozzle (first layer) | Bed |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Fine | 0.10 mm | 0.12 mm | 10 / 10 | 250 mm/s | 81 mm/s | 200 °C (210) | 60 °C |
| Normal | 0.15 mm | 0.15 mm | 6 / 6 | 250 mm/s | 81 mm/s | 200 °C (210) | 60 °C |
| Fast | 0.20 mm | 0.20 mm | 4 / 4 | 250 mm/s | 125 mm/s | 200 °C (210) | 60 °C |

All three presets use the same Neptune 4 Pro settings:
- Bed 235 × 230 × 270 mm.
- 0.42 mm lines, 2 walls, 15% grid infill and a 5 mm brim.
- 0.5 mm retraction at 45 mm/s.
- Acceleration 10,000 mm/s² (5,000 for walls, 500 for the first layer).
- Elegoo's start G-code, which heats the bed, homes, heats the nozzle and draws a prime line along the front edge.

## Files

| File | Purpose |
| --- | --- |
| `build_profiles.py` | Builds the presets into the app's assets |
| `test_build_profiles.py` | Tests the evaluator, the stack order, G-code placeholders, and hand-checked values in the real presets |
| `slice_desktop.py` | Slices a model with the desktop engine and a profile, using the same arguments as the app |
| `check_gcode.py` | Checks G-code against its profile: layer heights, temperatures, speeds per feature, travel, retraction, acceleration, fan and bed bounds |

## Usage

```sh
engine/fetch-sources.sh                          # Cura 5.13.0 files and fdm_materials 5.13.0
python3 profiles/build_profiles.py               # writes android/engine/src/main/assets/profiles/*.json
python3 -m unittest discover -s profiles         # 13 tests
python3 profiles/slice_desktop.py android/engine/src/main/assets/profiles/elegoo_neptune_4pro-pla-fast.json \
  engine/test/cube20.stl cube.gcode --scale 2.5
python3 profiles/check_gcode.py android/engine/src/main/assets/profiles/elegoo_neptune_4pro-pla-fast.json cube.gcode
```

## Desktop results

A 50 mm cube (the 20 mm test cube at 250%) passes all 21 checks with each preset. Slicing the 20 mm cube at 100% also passes. Its layers are so quick that the engine slows most of them down for the minimum layer time, as Cura would, so only a few layers can be checked at full speed.

Three things look like bugs but are correct:
- **Speeds above the profile's on organic models:** with `speed_equalize_flow_width_factor` at 100% (Cura's default), thinner lines print faster and wider ones slower, so the plastic flows at a constant rate. On a cube every line is the same width, but sculpts have walls of varying width. For these, `check_gcode.py` checks the volumetric flow instead: on the M4 device test it matched the profile to within 0.02 mm³/s for every feature, while speeds ranged from 49 to 297 mm/s.
- **Infill at 62.5 mm/s:** on the layer just below the top skin, Cura 5.13 prints *skin support* at `skin_support_speed` (62.5 mm/s).
- **Nozzle temperature change during layer 0:** the engine sends the layer-1 temperature (200 °C) near the end of layer 0, so the nozzle has cooled by the time layer 1 starts.

## Device results

| Date | Printer | Preset | Result |
| --- | --- | --- | --- |
| 2026-11-24 | Elegoo Neptune 4 Pro (by flash drive) | Normal · 0.15 mm, 20 mm calibration cube sliced on a Galaxy S25 Ultra | **M2 exit test passed.** The first layer and brim adhered evenly. The walls are clean and consistent at 250 mm/s, with sharp corners. The top surface is fully closed. The seam is aligned on one face, as the profile's "back" seam position intends. One under-filled band near the bottom matches a filament snap mid-print. |
