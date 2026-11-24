#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# Slice the M0 test cube with a given CuraEngine binary and print the numbers
# the M0 exit test compares: layer count, print time, filament used.
#
# Usage: slice-cube.sh <CuraEngine binary> <definitions dir> <output.gcode>
set -euo pipefail
ENGINE="$1"; DEFS="$2"; OUT="$3"
HERE="$(cd "$(dirname "$0")" && pwd)"

args=()
while IFS= read -r line; do
  [[ -z "$line" || "$line" == \#* ]] && continue
  args+=(-s "$line")
done < "$HERE/m0-settings.txt"

# --force-read-*: load parent settings and formula-only settings too, since there
# is no Cura frontend here to resolve them. The settings are applied globally
# and to extruder 0, whose own defaults would otherwise win (e.g. 2.85 mm filament).
"$ENGINE" slice -d "$DEFS" --force-read-parent --force-read-nondefault \
  -j "$DEFS/fdmprinter.def.json" "${args[@]}" \
  -e0 "${args[@]}" -l "$HERE/cube20.stl" -o "$OUT" > "$OUT.log" 2>&1

# In command-line mode the engine leaves placeholders in the G-code header
# (desktop Cura fills them in), so read time and filament from its log.
echo "layers: $(grep -m1 '^;LAYER_COUNT:' "$OUT" | cut -d: -f2)"
echo "print time (s): $(grep -m1 'Print time (s):' "$OUT.log" | sed 's/.*: //')"
echo "filament (mm^3): $(grep -m1 'Filament (mm^3):' "$OUT.log" | sed 's/.*: //')"
# Hash of the toolpaths only: drop comment lines, which can hold dates or versions.
echo "toolpath sha256: $(grep -v '^;' "$OUT" | sha256sum | cut -d' ' -f1)"
