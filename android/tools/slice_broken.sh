#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
# The M5 exit test on a desktop: slice every broken mesh as the app would.
# Run tools/broken_meshes.py and BrokenMeshesTest first (see android/README.md).
# Meshes with open edges get extensive stitching, as the app passes it;
# Fill holes results are sliced too. Usage: android/tools/slice_broken.sh
set -u
here=$(cd "$(dirname "$0")/.." && pwd)
root=$(cd "$here/.." && pwd)
in="$here/engine/build/broken-out"
out="$here/engine/build/broken-gcode"
profile="$here/engine/src/main/assets/profiles/elegoo_neptune_4pro-pla-normal.json"
mkdir -p "$out"
fail=0
for stl in "$in"/*.stl; do
  name=$(basename "$stl" .stl)
  open=$(grep "^${name%-filled}:" "$in/summary.txt" | sed -E 's/^[^|]*open ([0-9]+).*/\1/')
  extra=()
  if [[ "$name" != *-filled && "${open:-0}" != 0 ]]; then extra=(--set meshfix_extensive_stitching=True); fi
  if python3 "$root/profiles/slice_desktop.py" "$profile" "$stl" "$out/$name.gcode" "${extra[@]}" >/dev/null 2>&1; then
    layers=$(grep -c '^;LAYER:' "$out/$name.gcode")
    extrude=$(grep -cE '^G1 .*E[0-9]' "$out/$name.gcode")
    if [[ "$layers" == 0 ]]; then
      # No crash, but nothing to print: the app reports this and points to Fill holes.
      echo "EMPTY $name${extra:+ (stitching)}: no layers (the app says so and suggests Fill holes)"
    else
      echo "PASS  $name${extra:+ (stitching)}: $layers layers, $extrude extrusion moves"
    fi
  else
    echo "FAIL  $name: engine failed, see $out/$name.log"; fail=1
  fi
done
exit $fail
