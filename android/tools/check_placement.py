#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Check that CuraEngine puts each object where the app draws it.

Slices the objects in app/build/placed-objects.json (written by
ViewportScenesTest) with the desktop engine, using the same arguments as
EngineRunner.buildCommand, then compares each object's extruded extent in the
G-code with the bounds the app computed.

Usage: android/tools/check_placement.py PROFILE.json OUT_DIR
"""
import json
import math
import os
import struct
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REPO = ROOT.parent
WORK = Path(os.environ.get("WORK", Path.home() / "mobileslicer-build"))


def box_stl(path, x0, y0, z0, x1, y1, z1):
    v = [(x, y, z) for x in (x0, x1) for y in (y0, y1) for z in (z0, z1)]
    q = [(0, 1, 3, 2), (4, 6, 7, 5), (0, 4, 5, 1), (2, 3, 7, 6), (0, 2, 6, 4), (1, 5, 7, 3)]
    tris = [t for a, b, c, d in q for t in ((a, b, c), (a, c, d))]
    with open(path, "wb") as f:
        f.write(b"\0" * 80 + struct.pack("<I", len(tris)))
        for t in tris:
            f.write(struct.pack("<3f", 0, 0, 0) + b"".join(struct.pack("<3f", *v[i]) for i in t) + b"\0\0")


def main() -> int:
    profile = json.loads(Path(sys.argv[1]).read_text())["settings"]
    out = Path(sys.argv[2]); out.mkdir(parents=True, exist_ok=True)
    objs = json.loads((ROOT / "app/build/placed-objects.json").read_text())
    # The meshes named in the scene test.
    meshes = {"cube": REPO / "engine/test/cube20.stl", "ybox": out / "ybox.stl"}
    box_stl(meshes["ybox"], -5, 10, -2, 5, 50, 8)
    defs = WORK / "src/cura/resources/definitions"
    s = [a for k, v in profile.items() for a in ("-s", f"{k}={v}")]
    cx, cy = float(profile["machine_width"]) / 2, float(profile["machine_depth"]) / 2
    cmd = [str(WORK / "host/bin/CuraEngine"), "slice", "-d", str(defs), "--force-read-parent", "--force-read-nondefault",
           "-j", str(defs / "fdmprinter.def.json"), *s, "-e0", *s]
    # One copy of each mesh file per object, so the G-code's ;MESH: names tell them apart.
    for i, o in enumerate(objs):
        copy = out / f"object{i}.stl"
        copy.write_bytes(meshes[o["mesh"]].read_bytes())
        cmd += ["-e0", "-s", f"mesh_rotation_matrix={o['matrix']}", "-l", str(copy),
                "-s", "center_object=True", "-s", f"mesh_position_x={o['x'] - cx}", "-s", f"mesh_position_y={o['y'] - cy}",
                "-s", "mesh_position_z=0"]
    gcode = out / "placed.gcode"
    cmd += ["-o", str(gcode)]
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stdout[-2000:] + r.stderr[-2000:]); return 1

    ext = {}
    mesh = None; x = y = z = e = 0.0
    for line in gcode.read_text().splitlines():
        if line.startswith(";MESH:"):
            mesh = Path(line[6:].strip()).stem; continue
        p = line.split(";")[0].split()
        if not p: continue
        if p[0] in ("G0", "G1"):
            d = {w[0]: float(w[1:]) for w in p[1:] if w[0] in "XYZE"}
            nx, ny, nz = d.get("X", x), d.get("Y", y), d.get("Z", z)
            if "E" in d and d["E"] > e and mesh and mesh.startswith("object"):
                b = ext.setdefault(mesh, [math.inf] * 3 + [-math.inf] * 3)
                b[:] = [min(b[0], x, nx), min(b[1], y, ny), min(b[2], nz), max(b[3], x, nx), max(b[4], y, ny), max(b[5], nz)]
            if "E" in d: e = d["E"]
            x, y, z = nx, ny, nz
        elif p[0] == "G92":
            e = 0.0

    failed = 0
    for i, o in enumerate(objs):
        got = ext.get(f"object{i}")
        want = o["expected"]
        # Extrusion lines run half a line width inside the model's outline, and the
        # first layer is printed at its layer height rather than Z = 0.
        tol_xy, tol_z = 0.6, float(profile["layer_height_0"]) + 0.01
        ok = got is not None and all(abs(got[k] - want[k]) <= tol_xy for k in (0, 1, 3, 4)) \
            and abs(got[5] - want[5]) <= float(profile["layer_height"]) + 0.01 and got[2] <= tol_z
        failed += not ok
        fmt = lambda b: "X %.1f..%.1f Y %.1f..%.1f Z %.1f..%.1f" % (b[0], b[3], b[1], b[4], b[2], b[5])
        print(("PASS" if ok else "FAIL") + f"  object {i} ({o['mesh']}): app {fmt(want)} | engine {fmt(got) if got else 'none'}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
