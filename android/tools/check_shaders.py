#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Validate the GLSL ES shaders embedded in Kotlin sources with Khronos glslangValidator.

Finds Kotlin raw-string constants named *_VS and *_FS and compiles each as a
vertex or fragment shader. Usage: android/tools/check_shaders.py
"""
import re
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PATTERN = re.compile(r'const val (\w+_(VS|FS)) = """(.*?)"""', re.S)


def main() -> int:
    failures = 0
    count = 0
    with tempfile.TemporaryDirectory() as tmp:
        for src in sorted(ROOT.glob("*/src/main/java/**/*.kt")):
            for name, kind, body in PATTERN.findall(src.read_text(encoding="utf-8")):
                count += 1
                path = Path(tmp) / f"{name}.{'vert' if kind == 'VS' else 'frag'}"
                path.write_text(body, encoding="utf-8")
                r = subprocess.run(["glslangValidator", str(path)], capture_output=True, text=True)
                ok = r.returncode == 0
                failures += not ok
                print(f"{'ok  ' if ok else 'FAIL'} {src.name}: {name}")
                if not ok:
                    print(r.stdout + r.stderr)
    print(f"{count - failures} of {count} shaders compile as GLSL ES 3.00")
    return 1 if failures or count == 0 else 0


if __name__ == "__main__":
    sys.exit(main())
