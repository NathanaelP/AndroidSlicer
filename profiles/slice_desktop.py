#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Slice a model with the desktop CuraEngine build and a MobileSlicer profile.

Passes the engine exactly what the app's EngineRunner passes, so desktop and
device runs can be compared.

Usage: profiles/slice_desktop.py PROFILE.json MODEL.stl OUT.gcode [--scale 1.0] [--set KEY=VALUE ...]
"""
import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

WORK = Path(os.environ.get("WORK", Path.home() / "mobileslicer-build"))


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("profile", type=Path)
    ap.add_argument("model", type=Path)
    ap.add_argument("out", type=Path)
    ap.add_argument("--scale", type=float, default=1.0)
    ap.add_argument("--set", action="append", default=[], metavar="KEY=VALUE",
                    help="a setting for this object only, as the app passes after -l (such as mesh fixes)")
    ap.add_argument("--engine", type=Path, default=WORK / "host" / "bin" / "CuraEngine")
    ap.add_argument("--defs", type=Path, default=WORK / "src" / "cura" / "resources" / "definitions")
    args = ap.parse_args()

    settings = json.loads(args.profile.read_text(encoding="utf-8"))["settings"]
    s_args = [a for k, v in settings.items() for a in ("-s", f"{k}={v}")]
    m = args.scale
    cmd = [str(args.engine), "slice", "-d", str(args.defs), "--force-read-parent", "--force-read-nondefault",
           "-j", str(args.defs / "fdmprinter.def.json"), *s_args, "-e0", *s_args,
           "-s", "center_object=True", "-s", f"mesh_rotation_matrix=[[{m},0,0],[0,{m},0],[0,0,{m}]]",
           "-l", str(args.model), *[a for kv in args.set for a in ("-s", kv)], "-o", str(args.out)]
    log = args.out.with_suffix(".log")
    with log.open("w") as f:
        rc = subprocess.run(cmd, stdout=f, stderr=subprocess.STDOUT).returncode
    text = log.read_text(errors="replace")
    for line in text.splitlines():
        if "Print time (s):" in line or "Filament (mm^3):" in line:
            print(line.split("] ", 2)[-1])
    if rc != 0:
        print(f"engine exited with {rc}; see {log}", file=sys.stderr)
    return rc


if __name__ == "__main__":
    sys.exit(main())
