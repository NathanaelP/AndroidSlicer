# SPDX-License-Identifier: AGPL-3.0-or-later
"""Tests for build_profiles.py. Run: python3 -m unittest discover profiles"""
import math
import os
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_profiles as bp  # noqa: E402

SRC = Path(os.environ.get("MOBILESLICER_SRC", Path.home() / "mobileslicer-build" / "src"))


class EvaluatorTest(unittest.TestCase):
    def setUp(self):
        self.values = {"a": 2, "b": 0.5, "pattern": "grid", "flag": True}
        self.ev = bp.Evaluator(lambda k: self.values[k], {"extruderValues": lambda k: [self.values[k]]})

    def test_arithmetic_and_math(self):
        self.assertEqual(self.ev.evaluate("a * 3 + b"), 6.5)
        self.assertEqual(self.ev.evaluate("math.ceil(a * 1.2)"), 3)
        self.assertEqual(self.ev.evaluate("a ** 2 % 3"), 1)
        self.assertAlmostEqual(self.ev.evaluate("math.tan(math.radians(45))"), 1.0)

    def test_conditions_and_comparisons(self):
        self.assertEqual(self.ev.evaluate("'lines' if a > 1 and pattern == 'grid' else 'grid'"), "lines")
        self.assertTrue(self.ev.evaluate("0 < b < 1"))
        self.assertFalse(self.ev.evaluate("not flag"))
        self.assertTrue(self.ev.evaluate("pattern in ['grid', 'lines']"))

    def test_functions_and_comprehensions(self):
        self.assertEqual(self.ev.evaluate("max(extruderValues('a'))"), 2)
        self.assertTrue(self.ev.evaluate("any(x > 1 for x in extruderValues('a'))"))
        self.assertEqual(self.ev.evaluate("sum(map(float, [1, 2]))"), 3.0)
        self.assertEqual(self.ev.evaluate("min(['skirt', 'brim'], key=('raft', 'brim', 'skirt', 'none').index)"), "brim")
        self.assertEqual(self.ev.evaluate("[1, 2, 3][a]"), 3)

    def test_rejects_anything_else(self):
        for bad in ["__import__('os')", "open('x')", "a.__class__", "(lambda: 1)()", "pattern.upper()",
                    "[x for x in ().__class__.__bases__]", "b.real"]:
            with self.subTest(bad=bad), self.assertRaises((bp.FormulaError, KeyError)):
                self.ev.evaluate(bad)


class ResolverTest(unittest.TestCase):
    def make(self, layers):
        defs = {
            "speed_print": {"type": "float", "default_value": 60},
            "speed_wall": {"type": "float", "default_value": 30, "value": "speed_print / 2"},
            "count": {"type": "int", "default_value": 1, "value": "math.ceil(speed_wall / 10)"},
            "pattern": {"type": "enum", "default_value": "grid"},
            "enabled": {"type": "bool", "default_value": False},
        }
        return bp.Resolver(defs, layers)

    def test_definition_formulas(self):
        r = self.make([])
        self.assertEqual(r.get("speed_wall"), 30)
        self.assertEqual(r.get("count"), 3)

    def test_higher_layers_win_and_literals_are_typed(self):
        r = self.make([bp.Layer("quality", {"speed_wall": "=speed_print * 0.8"}),
                       bp.Layer("material", {"speed_print": "100", "speed_wall": "10", "enabled": "yes"})])
        self.assertEqual(r.get("speed_print"), 100.0)
        self.assertEqual(r.get("speed_wall"), 80.0)  # quality formula beats the material literal
        self.assertEqual(r.get("count"), 8)
        self.assertIs(r.get("enabled"), True)

    def test_cycles_are_reported(self):
        r = bp.Resolver({"x": {"type": "float", "value": "y"}, "y": {"type": "float", "value": "x"}}, [])
        with self.assertRaises(bp.FormulaError):
            r.get("x")

    def test_gcode_placeholders_and_conditions(self):
        r = self.make([])
        text = "M140 S{speed_print}\n{if enabled}\nG29\n{else}\nG28\n{endif}\nG1 Y{speed_print - 5}\n;{print_time}"
        self.assertEqual(bp.expand_gcode(text, r), "M140 S60\nG28\nG1 Y55\n;{print_time}")


@unittest.skipUnless((SRC / "cura" / "resources").is_dir() and (SRC / "fdm_materials").is_dir(),
                     "Cura sources not fetched (engine/fetch-sources.sh)")
class RealProfilesTest(unittest.TestCase):
    """Values checked by hand against Cura 5.13's Elegoo Neptune 4 Pro files and Generic PLA."""

    @classmethod
    def setUpClass(cls):
        cls.p = {p.id: bp.build_preset(SRC, p)["settings"] for p in bp.PRESETS}

    def test_machine(self):
        s = self.p["fast"]
        self.assertEqual((s["machine_width"], s["machine_depth"], s["machine_height"]), ("235", "230", "270"))
        self.assertEqual(s["machine_nozzle_size"], "0.4")
        self.assertEqual(s["material_diameter"], "1.75")

    def test_layer_heights(self):
        self.assertEqual([self.p[q]["layer_height"] for q in ("fine", "normal", "fast")], ["0.1", "0.15", "0.2"])
        self.assertEqual([self.p[q]["layer_height_0"] for q in ("fine", "normal", "fast")], ["0.12", "0.15", "0.2"])

    def test_temperatures(self):
        for s in self.p.values():
            self.assertEqual(float(s["material_print_temperature"]), 200)        # Generic PLA
            self.assertEqual(float(s["material_print_temperature_layer_0"]), 210)  # Elegoo base: at least 210
            self.assertEqual(float(s["cool_min_temperature"]), 200)              # the M1 bug: not 0
            self.assertEqual(float(s["material_bed_temperature"]), 60)
            # Elegoo's start G-code heats both, so the engine mustn't add its own heating.
            self.assertEqual(s["material_print_temp_prepend"], "False")
            self.assertEqual(s["material_bed_temp_prepend"], "False")
            self.assertIn("M190 S60.0", s["machine_start_gcode"])
            self.assertIn("M109 S210", s["machine_start_gcode"])
            self.assertNotIn("{", s["machine_start_gcode"])
            self.assertIn("G1 X0 Y225", s["machine_end_gcode"])  # {machine_depth - 5}

    def test_speeds(self):
        fine, fast = self.p["fine"], self.p["fast"]
        self.assertEqual(fast["speed_print"], "250")
        # PLA quality files for 0.10/0.15: speed_wall = ceil(250 * 45/70), speed_wall_0 = ceil(161 * 35/70)
        self.assertEqual((fine["speed_wall"], fine["speed_wall_0"], fine["speed_topbottom"]), ("161", "81", "125"))
        # 0.20 has no speed overrides: Neptune 4 formulas speed_print / 2 and speed_wall * 1.5
        self.assertEqual((float(fast["speed_wall"]), float(fast["speed_wall_x"])), (125, 187.5))
        self.assertEqual(fast["speed_infill"], "250")
        self.assertEqual(fast["speed_layer_0"], "60")

    def test_walls_infill_retraction(self):
        s = self.p["fast"]
        self.assertAlmostEqual(float(s["line_width"]), 0.42)
        self.assertEqual(s["wall_line_count"], "2")
        self.assertEqual((s["top_layers"], s["bottom_layers"]), ("4", "4"))  # 0.8 mm / 0.2 mm
        self.assertEqual(s["infill_pattern"], "grid")
        # grid: infill_line_width * 100 / density * 2 = 0.47 * 100 / 15 * 2
        self.assertAlmostEqual(float(s["infill_line_distance"]), 0.47 * 100 / 15 * 2)
        self.assertEqual((s["retraction_amount"], s["retraction_retract_speed"]), ("0.5", "45"))


if __name__ == "__main__":
    unittest.main()
