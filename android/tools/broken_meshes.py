#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Write deliberately broken meshes for the M5 exit test.

Each is a binary STL in mm, sized to print. BrokenMeshesTest imports them the
way the app does (edge check, Fill holes); tools/slice_broken.sh then slices
them with the desktop engine.

Usage: android/tools/broken_meshes.py OUT_DIR
"""
import math
import random
import struct
import sys
from pathlib import Path


def write(path, tris):
    with open(path, "wb") as f:
        f.write(b"broken mesh test".ljust(80, b" "))
        f.write(struct.pack("<I", len(tris)))
        for a, b, c in tris:
            f.write(struct.pack("<3f", 0, 0, 0))
            for p in (a, b, c):
                f.write(struct.pack("<3f", *p))
            f.write(b"\0\0")


def sphere(r, cx=0.0, cy=0.0, cz=0.0, lat=48, lon=96):
    """Outward-facing UV sphere; returns triangles tagged with their (ring, segment)."""
    def p(i, j):
        t = math.pi * i / lat
        f = 2 * math.pi * j / lon
        # Rounded, so the seam and the poles meet exactly: the plain sphere is watertight.
        return tuple(round(v, 4) + 0.0 for v in (cx + r * math.sin(t) * math.cos(f), cy + r * math.sin(t) * math.sin(f), cz + r * math.cos(t)))
    out = []
    for i in range(lat):
        for j in range(lon):
            a, b, c, d = p(i, j), p(i + 1, j), p(i + 1, j + 1), p(i, j + 1)
            if i != 0:
                out.append(((i, j), (a, b, d)))
            if i != lat - 1:
                out.append(((i, j), (b, c, d)))
    return out


def box(x0, y0, z0, x1, y1, z1, skip=()):
    v = lambda x, y, z: ((x0, x1)[x], (y0, y1)[y], (z0, z1)[z])
    faces = {
        "bottom": [(0, 0, 0), (0, 1, 0), (1, 1, 0), (1, 0, 0)],
        "top": [(0, 0, 1), (1, 0, 1), (1, 1, 1), (0, 1, 1)],
        "front": [(0, 0, 0), (1, 0, 0), (1, 0, 1), (0, 0, 1)],
        "back": [(0, 1, 0), (0, 1, 1), (1, 1, 1), (1, 1, 0)],
        "left": [(0, 0, 0), (0, 0, 1), (0, 1, 1), (0, 1, 0)],
        "right": [(1, 0, 0), (1, 1, 0), (1, 1, 1), (1, 0, 1)],
    }
    out = []
    for name, q in faces.items():
        if name in skip:
            continue
        a, b, c, d = (v(*k) for k in q)
        out += [(a, b, c), (a, c, d)]
    return out


def main():
    out = Path(sys.argv[1] if len(sys.argv) > 1 else ".")
    out.mkdir(parents=True, exist_ok=True)
    rnd = random.Random(5)
    s = sphere(20, cz=20)
    write(out / "clean-sphere.stl", [t for _, t in s])
    # A head with its top cut off and a hole in the side: two holes, like a sculpt cut open.
    write(out / "holes.stl", [t for (i, j), t in s if i > 4 and not (20 <= i < 26 and 10 <= j < 18)])
    # A box with a whole side wall missing: every layer's outline is open.
    write(out / "missing-wall.stl", box(0, 0, 0, 30, 20, 15, skip=("front",)))
    # Inside-out faces scattered over the sphere.
    write(out / "flipped.stl", [(t[0], t[2], t[1]) if rnd.random() < 0.1 else t for _, t in s])
    # Every face written twice, plus two cubes touching along one edge.
    write(out / "doubled-and-edge.stl", [t for _, t in s] * 2 + box(40, 0, 0, 50, 10, 10) + box(50, 10, 0, 60, 20, 10))
    # Zero-area triangles and non-finite corners mixed in.
    bad = [t for _, t in s]
    bad += [((0, 0, 0), (0, 0, 0), (1, 1, 1)), ((5, 5, 5), (6, 6, 6), (7, 7, 7)), ((float("nan"), 0, 0), (1, 0, 0), (0, 1, 0)),
            ((float("inf"), 0, 0), (1, 0, 0), (0, 1, 0))]
    write(out / "degenerate.stl", bad)
    # A triangle soup: no two triangles share a corner exactly (tiny offsets), so nothing is joined.
    soup = []
    for _, t in s:
        e = lambda p: tuple(c + rnd.uniform(-1e-3, 1e-3) for c in p)
        soup.append((e(t[0]), e(t[1]), e(t[2])))
    write(out / "soup.stl", soup)
    # Everything at once, with random triangles deleted.
    worst = [t for (i, j), t in s if rnd.random() > 0.02]
    worst = [(t[0], t[2], t[1]) if rnd.random() < 0.05 else t for t in worst] + worst[:200]
    write(out / "everything.stl", worst)
    print("\n".join(sorted(p.name for p in out.glob("*.stl"))))


if __name__ == "__main__":
    main()
