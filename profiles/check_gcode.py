#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Check that G-code matches the profile it was sliced with (the M2 exit test).

Checks layer heights, nozzle and bed temperatures, print speed per feature,
travel speed, retraction, acceleration, fan, and that everything stays on the
bed. Speeds are checked only on layers at full speed: after the first
`speed_slowdown_layers` layers and not slowed down for the minimum layer time.

Usage: profiles/check_gcode.py PROFILE.json FILE.gcode
Exit status 0 if every check passes.
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from collections import Counter, defaultdict
from pathlib import Path

TOL = 1e-3


def parse_words(code: str) -> tuple[str, dict[str, float]]:
    words = code.split()
    params: dict[str, float] = {}
    for w in words[1:]:
        try:
            params[w[0]] = float(w[1:])
        except ValueError:
            pass
    return words[0], params


def check(profile: dict, gcode_lines: list[str]) -> list[tuple[bool, str]]:
    s = profile["settings"]
    f = lambda k: float(s[k])
    results: list[tuple[bool, str]] = []

    def expect(ok: bool, msg: str) -> None:
        results.append((ok, msg))

    # State while walking the file.
    x = y = z = e = 0.0
    feed = 0.0
    layer: int | None = None
    feature = ""
    slowed: set[int] = set()
    layer_z: dict[int, float] = {}
    speeds: dict[str, Counter] = defaultdict(Counter)   # feature -> Counter(mm/s) on full-speed layers
    travel: Counter = Counter()
    retracts: Counter = Counter()                       # (amount, mm/s)
    primes: Counter = Counter()
    nozzle_cmds: list[tuple[int | None, str, float]] = []
    bed_cmds: list[tuple[int | None, str, float]] = []
    accels: Counter = Counter()
    fans: list[tuple[int | None, float]] = []
    bounds = [math.inf, math.inf, -math.inf, -math.inf]
    slowdown_layers = int(f("speed_slowdown_layers"))
    end_reached = False

    last_elapsed = max((i for i, l in enumerate(gcode_lines) if l.startswith(";TIME_ELAPSED:")), default=len(gcode_lines))
    for i_line, raw in enumerate(gcode_lines):
        line = raw.rstrip("\n")
        if i_line > last_elapsed:
            end_reached = True
        if line.startswith(";LAYER:"):
            layer = int(line[7:]); continue
        if line.startswith(";TYPE:"):
            feature = line[6:].strip(); continue
        if line.startswith(";note -- min layer time used") and layer is not None:
            slowed.add(layer); continue
        if line.startswith(";TIME_ELAPSED:"):
            continue
        code = line.split(";", 1)[0].strip()
        if not code:
            continue
        cmd, p = parse_words(code)
        if cmd in ("M104", "M109") and "S" in p:
            nozzle_cmds.append((layer, cmd, p["S"]))
        elif cmd in ("M140", "M190") and "S" in p:
            bed_cmds.append((layer, cmd, p["S"]))
        elif cmd == "M204" and not end_reached:
            for k in ("S", "P", "T"):
                if k in p: accels[(layer, p[k])] += 1
        elif cmd == "M106":
            fans.append((layer, p.get("S", 255.0)))
        elif cmd == "M107":
            fans.append((layer, 0.0))
        elif cmd == "G92":
            e = p.get("E", e)
        elif cmd in ("G0", "G1") and not end_reached:
            feed = p.get("F", feed)
            nx, ny, nz = p.get("X", x), p.get("Y", y), p.get("Z", z)
            moved = nx != x or ny != y
            if "E" in p and layer is not None:
                de = p["E"] - e
                if moved and de > 0:
                    if layer not in layer_z: layer_z[layer] = nz
                    bounds = [min(bounds[0], x, nx), min(bounds[1], y, ny), max(bounds[2], x, nx), max(bounds[3], y, ny)]
                    if layer >= slowdown_layers and layer not in slowed:
                        speeds[feature][round(feed / 60, 2)] += 1
                elif not moved and nz == z and de < 0:
                    retracts[(round(-de, 4), round(feed / 60, 2))] += 1
                elif not moved and nz == z and de > 0:
                    primes[(round(de, 4), round(feed / 60, 2))] += 1
                e = p["E"]
            elif cmd == "G0" and moved and layer is not None and layer >= slowdown_layers and layer not in slowed and not end_reached:
                travel[round(feed / 60, 2)] += 1
            x, y, z = nx, ny, nz

    # Layer heights
    lh0, lh = f("layer_height_0"), f("layer_height")
    expect(abs(layer_z.get(0, -1) - lh0) < TOL, f"first layer at Z {layer_z.get(0)} (profile layer_height_0 {lh0})")
    steps = [round(layer_z[i] - layer_z[i - 1], 4) for i in sorted(layer_z) if i - 1 in layer_z]
    bad = [d for d in steps if abs(d - lh) > TOL]
    expect(not bad and len(steps) > 0, f"{len(steps)} layer steps, all {lh} mm" if not bad else f"layer steps off: {Counter(bad)}")

    # Temperatures
    t0, t = f("material_print_temperature_layer_0"), f("material_print_temperature")
    waits = [v for (l, c, v) in nozzle_cmds if c == "M109" and l is None]
    expect(t0 in waits, f"waits for nozzle {t0} C before printing (found {waits})")
    # The engine sets the layer-1 temperature during layer 0, so the nozzle has cooled by the time layer 1 starts.
    later = sorted({v for (l, c, v) in nozzle_cmds if l is not None and v != 0})
    expect(later == [t] or (not later and t == t0), f"nozzle {t} C after the first layer (temperatures set while printing: {later})")
    off_mid = [(l, c, v) for (l, c, v) in nozzle_cmds if l is not None and v == 0]
    max_layer = max(layer_z) if layer_z else 0
    expect(all(l >= max_layer for (l, _, _) in off_mid), f"nozzle only switched off at the end (found {off_mid})")
    b0, b = f("material_bed_temperature_layer_0"), f("material_bed_temperature")
    bed_start = [v for (l, c, v) in bed_cmds if l is None and c == "M190"]
    expect(b0 in bed_start, f"waits for bed {b0} C before printing (found {bed_start})")
    bed_later = sorted({v for (l, c, v) in bed_cmds if l is not None and v != 0})
    expect(bed_later in ([], [b]), f"bed {b} C after the first layer (found {bed_later or 'no change'})")

    # Speeds per feature on full-speed layers
    feature_speed = {
        "WALL-OUTER": "speed_wall_0", "WALL-INNER": "speed_wall_x", "FILL": "speed_infill",
        "SKIN": "speed_topbottom", "SUPPORT": "speed_support_infill", "SKIRT": "skirt_brim_speed",
    }
    full_layers = [i for i in layer_z if i >= slowdown_layers and i not in slowed]
    expect(len(full_layers) > 0, f"{len(full_layers)} layers at full speed to check speeds on "
                                 f"(slow-down layers {slowdown_layers}, min-layer-time layers {len(slowed)})")
    for feat, counter in sorted(speeds.items()):
        if feat not in feature_speed or not counter:
            continue
        key = feature_speed[feat]
        want = f(key)
        allowed = {want}
        if feat == "FILL" and s.get("skin_support") == "True":
            allowed.add(f("skin_support_speed"))  # support for the top skin, on the layer below it
        found = sorted(counter)
        ok = all(any(abs(v - a) < 0.05 for a in allowed) for v in found)
        note = "" if want in found else f"; {key} itself isn't used on any full-speed layer of this model"
        expect(ok, f"{feat}: {found} mm/s (profile {key} {want}" +
               (f", skin_support_speed {f('skin_support_speed')}" if len(allowed) > 1 else "") + note + ")")
    if travel:
        want = f("speed_travel")
        top = max(travel)
        expect(abs(top - want) < 0.05, f"travel: {top} mm/s (profile speed_travel {want})")

    # Retraction
    if s["retraction_enable"] == "True":
        amt, rs, ps = f("retraction_amount"), f("retraction_retract_speed"), f("retraction_prime_speed")
        main_r = [k for k in retracts if abs(k[0] - amt) < TOL]
        expect(bool(main_r), f"retracts {amt} mm (found amounts {sorted({k[0] for k in retracts})})")
        expect(all(abs(k[1] - rs) < 0.05 for k in main_r), f"retract speed {rs} mm/s (found {sorted({k[1] for k in main_r})})")
        main_p = [k for k in primes if abs(k[0] - amt) < TOL]
        expect(all(abs(k[1] - ps) < 0.05 for k in main_p), f"prime speed {ps} mm/s (found {sorted({k[1] for k in main_p})})")

    # Acceleration
    if s["acceleration_enabled"] == "True":
        allowed = {f(k) for k in s if k.startswith("acceleration_") and k != "acceleration_enabled"
                   and s[k].replace(".", "", 1).isdigit()}
        # Like speeds, accelerations ramp up over the first speed_slowdown_layers layers.
        used = sorted({v for (l, v) in accels if l is None or l >= slowdown_layers})
        expect(bool(used) and set(used) <= allowed, f"accelerations after the first {slowdown_layers} layers {used} all come from the profile")
        first = sorted({v for (l, v) in accels if l == 0})
        expect(f("acceleration_layer_0") in first or not first, f"first layer acceleration {f('acceleration_layer_0')} (found {first})")

    # Fan
    full_fan = int(f("cool_fan_full_layer"))
    fan_at_0 = [v for (l, v) in fans if l == 0]
    expect(all(v == 255 * f("cool_fan_speed_0") / 100 for v in fan_at_0), f"fan {s['cool_fan_speed_0']}% on the first layer (found {fan_at_0})")
    fan_full = [v for (l, v) in fans if l == full_fan]
    expect(255 * f("cool_fan_speed") / 100 in fan_full or any(l is not None and l < full_fan and v == 255 for (l, v) in fans),
           f"fan {s['cool_fan_speed']}% by layer {full_fan} (found {fan_full})")

    # On the bed
    w, d = f("machine_width"), f("machine_depth")
    expect(0 <= bounds[0] and 0 <= bounds[1] and bounds[2] <= w and bounds[3] <= d,
           f"extrusion within the {w:g} x {d:g} mm bed (X {bounds[0]:.1f}..{bounds[2]:.1f}, Y {bounds[1]:.1f}..{bounds[3]:.1f})")
    return results


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("profile", type=Path)
    ap.add_argument("gcode", type=Path)
    args = ap.parse_args()
    profile = json.loads(args.profile.read_text(encoding="utf-8"))
    results = check(profile, args.gcode.read_text(errors="replace").splitlines())
    for ok, msg in results:
        print(("PASS  " if ok else "FAIL  ") + msg)
    failed = sum(not ok for ok, _ in results)
    print(f"\n{len(results) - failed} of {len(results)} checks passed for {profile['name']}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
