#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Build MobileSlicer's printer profiles from Cura's own definitions.

Desktop Cura works out most settings from formulas ("value": "speed_print / 2")
in its frontend, before calling CuraEngine. The engine on its own only knows
each setting's fallback default, which is often wrong and sometimes dangerous
(M1 found cool_min_temperature falling back to 0 C). This script does what the
Cura frontend does, offline, and writes one flat profile per preset:

1. Stack the settings layers in Cura's order, lowest first: the definition
   chain (fdmprinter, Elegoo base, Neptune 4, Neptune 4 Pro) and extruder
   (fdmextruder, Elegoo extruder 0), the nozzle variant, the material, the
   global quality preset, then the material-specific quality.
2. Evaluate every setting's formula with a small, restricted evaluator that
   understands the subset of Python Cura's formulas use (no eval()).
3. Apply Cura's pre-slice steps (StartSliceJob): decide whether the engine
   should prepend heating commands, and fill in {placeholders} in the start
   and end G-code.

Usage: profiles/build_profiles.py [--src ~/mobileslicer-build/src] [--out DIR]
Sources come from engine/fetch-sources.sh.
"""
from __future__ import annotations

import argparse
import ast
import configparser
import json
import math
import re
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable

HERE = Path(__file__).resolve().parent
REPO = HERE.parent


# --------------------------------------------------------------------------
# Restricted formula evaluator
# --------------------------------------------------------------------------

class FormulaError(Exception):
    pass


_BINOPS: dict[type, Callable[[Any, Any], Any]] = {
    ast.Add: lambda a, b: a + b, ast.Sub: lambda a, b: a - b,
    ast.Mult: lambda a, b: a * b, ast.Div: lambda a, b: a / b,
    ast.FloorDiv: lambda a, b: a // b, ast.Mod: lambda a, b: a % b,
    ast.Pow: lambda a, b: a ** b,
}
_CMPOPS: dict[type, Callable[[Any, Any], bool]] = {
    ast.Eq: lambda a, b: a == b, ast.NotEq: lambda a, b: a != b,
    ast.Lt: lambda a, b: a < b, ast.LtE: lambda a, b: a <= b,
    ast.Gt: lambda a, b: a > b, ast.GtE: lambda a, b: a >= b,
    ast.In: lambda a, b: a in b, ast.NotIn: lambda a, b: a not in b,
}
_BUILTINS: dict[str, Callable[..., Any]] = {
    "max": max, "min": min, "round": round, "sum": sum, "len": len, "int": int,
    "float": float, "str": str, "bool": bool, "any": any, "all": all, "abs": abs,
    "map": lambda f, it: list(map(f, it)), "list": list, "sorted": sorted,
}
_MATH = {name: getattr(math, name) for name in (
    "ceil", "floor", "sqrt", "tan", "atan", "sin", "cos", "radians", "degrees", "pi", "log", "exp", "fabs")}


class _Index:
    """seq.index of a literal sequence, callable as a function or a sort key."""
    def __init__(self, seq: Any):
        self.seq = seq

    def __call__(self, item: Any) -> int:
        return self.seq.index(item)


class Evaluator:
    """Interprets a parsed formula. Names are looked up through [lookup]."""

    def __init__(self, lookup: Callable[[str], Any], functions: dict[str, Callable[..., Any]]):
        self.lookup = lookup
        self.functions = {**_BUILTINS, **functions}

    def evaluate(self, source: str, local: dict[str, Any] | None = None) -> Any:
        try:
            tree = ast.parse(source.strip(), mode="eval")
        except SyntaxError as e:
            raise FormulaError(f"can't parse {source!r}: {e}") from e
        return self._eval(tree.body, local or {})

    def _eval(self, n: ast.AST, local: dict[str, Any]) -> Any:
        if isinstance(n, ast.Constant):
            return n.value
        if isinstance(n, ast.Name):
            if n.id in local:
                return local[n.id]
            if n.id in self.functions:
                return self.functions[n.id]
            return self.lookup(n.id)
        if isinstance(n, ast.Attribute):
            if isinstance(n.value, ast.Name) and n.value.id == "math" and n.attr in _MATH:
                return _MATH[n.attr]
            # The one method Cura's formulas use: ('raft', 'brim', ...).index as a sort key.
            if n.attr == "index" and isinstance(n.value, (ast.Tuple, ast.List)):
                return _Index(self._eval(n.value, local))
            raise FormulaError(f"attribute not allowed: {ast.unparse(n)}")
        if isinstance(n, ast.BinOp) and type(n.op) in _BINOPS:
            return _BINOPS[type(n.op)](self._eval(n.left, local), self._eval(n.right, local))
        if isinstance(n, ast.UnaryOp):
            v = self._eval(n.operand, local)
            if isinstance(n.op, ast.USub): return -v
            if isinstance(n.op, ast.UAdd): return +v
            if isinstance(n.op, ast.Not): return not v
        if isinstance(n, ast.BoolOp):
            if isinstance(n.op, ast.And):
                v: Any = True
                for x in n.values:
                    v = self._eval(x, local)
                    if not v: return v
                return v
            v = False
            for x in n.values:
                v = self._eval(x, local)
                if v: return v
            return v
        if isinstance(n, ast.Compare):
            left = self._eval(n.left, local)
            for op, comp in zip(n.ops, n.comparators):
                right = self._eval(comp, local)
                if type(op) not in _CMPOPS: break
                if not _CMPOPS[type(op)](left, right): return False
                left = right
            else:
                return True
        if isinstance(n, ast.IfExp):
            return self._eval(n.body if self._eval(n.test, local) else n.orelse, local)
        if isinstance(n, ast.Call):
            fn = self._eval(n.func, local)
            if not (isinstance(fn, _Index) or fn in self.functions.values() or fn in _MATH.values()):
                raise FormulaError(f"call not allowed: {ast.unparse(n.func)}")
            args = [self._eval(a, local) for a in n.args]
            kwargs = {k.arg: self._eval(k.value, local) for k in n.keywords if k.arg}
            return fn(*args, **kwargs)
        if isinstance(n, (ast.List, ast.Tuple)):
            items = [self._eval(e, local) for e in n.elts]
            return items if isinstance(n, ast.List) else tuple(items)
        if isinstance(n, ast.Subscript):
            return self._eval(n.value, local)[self._eval(n.slice, local)]
        if isinstance(n, (ast.GeneratorExp, ast.ListComp)):
            return self._comprehension(n.elt, n.generators, local)
        raise FormulaError(f"expression not allowed: {ast.unparse(n)}")

    def _comprehension(self, elt: ast.AST, gens: list[ast.comprehension], local: dict[str, Any]) -> list[Any]:
        out: list[Any] = []

        def loop(i: int, scope: dict[str, Any]) -> None:
            if i == len(gens):
                out.append(self._eval(elt, scope)); return
            g = gens[i]
            if not isinstance(g.target, ast.Name):
                raise FormulaError("only simple loop variables are allowed")
            for item in self._eval(g.iter, scope):
                inner = {**scope, g.target.id: item}
                if all(self._eval(c, inner) for c in g.ifs):
                    loop(i + 1, inner)
        loop(0, dict(local))
        return out


# --------------------------------------------------------------------------
# Settings layers
# --------------------------------------------------------------------------

@dataclass
class Layer:
    """One container in the stack: setting key -> raw value ("=formula" or literal string)."""
    name: str
    values: dict[str, str] = field(default_factory=dict)


def flatten(tree: dict[str, Any], out: dict[str, dict[str, Any]]) -> None:
    for key, props in tree.items():
        if props.get("type") != "category":
            out[key] = {k: v for k, v in props.items() if k != "children"}
        if "children" in props:
            flatten(props["children"], out)


def load_definition_chain(defs: Path, leaf: str) -> tuple[list[str], dict[str, dict[str, Any]]]:
    """Merges a definition and its ancestors, property by property, the way Cura does."""
    chain: list[dict[str, Any]] = []
    names: list[str] = []
    name: str | None = leaf
    while name:
        path = defs / f"{name}.def.json"
        data = json.loads(path.read_text(encoding="utf-8"))
        chain.append(data)
        names.append(name)
        name = data.get("inherits")
    chain.reverse(); names.reverse()
    settings: dict[str, dict[str, Any]] = {}
    for data in chain:
        if "settings" in data:
            flatten(data["settings"], settings)
        for key, props in data.get("overrides", {}).items():
            settings.setdefault(key, {}).update(props)
    return names, settings


def read_cfg(path: Path) -> tuple[dict[str, str], dict[str, str], dict[str, str]]:
    """Reads a Cura .inst.cfg: ([general], [metadata], [values])."""
    cp = configparser.ConfigParser(interpolation=None)
    cp.optionxform = str  # keep key case
    cp.read(path, encoding="utf-8")
    get = lambda s: dict(cp[s]) if cp.has_section(s) else {}
    return get("general"), get("metadata"), get("values")


# Cura's XmlMaterialProfile mappings (plugins/XmlMaterialProfile/XmlMaterialProfile.py).
MATERIAL_SETTINGS = {
    "print temperature": "default_material_print_temperature",
    "heated bed temperature": "default_material_bed_temperature",
    "standby temperature": "material_standby_temperature",
    "processing temperature graph": "material_flow_temp_graph",
    "print cooling": "cool_fan_speed",
    "retraction amount": "retraction_amount",
    "retraction speed": "retraction_speed",
    "adhesion tendency": "material_adhesion_tendency",
    "surface energy": "material_surface_energy",
    "build volume temperature": "build_volume_temperature",
    "anti ooze retract position": "material_anti_ooze_retracted_position",
    "anti ooze retract speed": "material_anti_ooze_retraction_speed",
    "break preparation position": "material_break_preparation_retracted_position",
    "break preparation speed": "material_break_preparation_speed",
    "break preparation temperature": "material_break_preparation_temperature",
    "break position": "material_break_retracted_position",
    "flush purge speed": "material_flush_purge_speed",
    "flush purge length": "material_flush_purge_length",
    "end of filament purge speed": "material_end_of_filament_purge_speed",
    "end of filament purge length": "material_end_of_filament_purge_length",
    "maximum park duration": "material_maximum_park_duration",
    "no load move factor": "material_no_load_move_factor",
    "break speed": "material_break_speed",
    "break temperature": "material_break_temperature",
}
NS = {"um": "http://www.ultimaker.com/material", "cura": "http://www.ultimaker.com/cura"}


@dataclass
class Material:
    layer: Layer
    metadata: dict[str, str]


def read_material(path: Path, machine_ids: list[str]) -> Material:
    """Reads an fdm_material XML file: common settings, plus any section for this machine."""
    root = ET.parse(path).getroot()
    values: dict[str, str] = {}
    meta: dict[str, str] = {}
    name = root.find("um:metadata/um:name", NS)
    if name is not None:
        for tag in ("brand", "material", "color"):
            el = name.find(f"um:{tag}", NS)
            if el is not None and el.text:
                meta[tag] = el.text.strip()
    guid = root.find("um:metadata/um:GUID", NS)
    if guid is not None and guid.text:
        meta["GUID"] = guid.text.strip()
        values["material_guid"] = meta["GUID"]
    if "brand" in meta: values["material_brand"] = meta["brand"]
    if "material" in meta: values["material_type"] = meta["material"]
    for prop in ("diameter", "density"):
        el = root.find(f"um:properties/um:{prop}", NS)
        if el is not None and el.text:
            meta[prop] = el.text.strip()
    if "diameter" in meta:
        values["material_diameter"] = meta["diameter"]

    def apply(settings_el: ET.Element) -> None:
        for el in settings_el.findall("um:setting", NS):
            key = el.get("key", "")
            if key == "processing temperature graph":
                points = [[float(p.get("flow")), float(p.get("temperature"))] for p in el.findall("um:point", NS)]
                values[MATERIAL_SETTINGS[key]] = str(points)
            elif key in MATERIAL_SETTINGS and el.text is not None:
                values[MATERIAL_SETTINGS[key]] = el.text.strip()
        for el in settings_el.findall("cura:setting", NS):
            if el.get("key") and el.text is not None:
                v = el.text.strip()
                values[el.get("key")] = {"yes": "True", "no": "False"}.get(v.lower(), v)

    settings = root.find("um:settings", NS)
    if settings is not None:
        apply(settings)
        for machine in settings.findall("um:machine", NS):
            products = {m.get("product") for m in machine.findall("um:machine_identifier", NS)}
            if products & set(machine_ids):
                apply(machine)
    return Material(Layer(f"material {path.name}", values), meta)


# --------------------------------------------------------------------------
# Resolver
# --------------------------------------------------------------------------

class Resolver:
    """Resolves every setting for a single-extruder printer, as Cura's stacks would."""

    def __init__(self, definitions: dict[str, dict[str, Any]], layers: list[Layer]):
        self.definitions = definitions
        self.layers = layers  # highest priority first
        self.cache: dict[str, Any] = {}
        self.in_progress: set[str] = set()
        self.extra: dict[str, Any] = {}
        self.evaluator = Evaluator(self.get, {
            # One extruder: every per-extruder query answers with this extruder's value.
            "extruderValue": lambda extruder, key: self.get(key),
            "extruderValues": lambda key: [self.get(key)],
            "resolveOrValue": self.resolve_or_value,
            "defaultExtruderPosition": lambda: "0",
            "anyExtruderWithMaterial": lambda key: "0",
            "anyExtruderNrWithOrDefault": lambda key: "0",
        })

    def convert(self, key: str, raw: str) -> Any:
        """Converts a literal from a .inst.cfg or material file to the setting's type."""
        t = self.definitions.get(key, {}).get("type", "str")
        s = raw.strip()
        if t == "float": return float(s)
        if t == "int": return int(float(s))
        if t == "bool": return s.lower() in ("true", "yes", "on", "1")
        if t in ("[int]", "polygon", "polygons"):
            return ast.literal_eval(s) if s else []
        return s

    def get(self, key: str) -> Any:
        if key in self.extra:
            return self.extra[key]
        if key in self.cache:
            return self.cache[key]
        if key not in self.definitions:
            raise FormulaError(f"unknown setting: {key}")
        if key in self.in_progress:
            raise FormulaError(f"circular formula through {key}")
        self.in_progress.add(key)
        try:
            value = self._compute(key)
        finally:
            self.in_progress.discard(key)
        self.cache[key] = value
        return value

    def source(self, key: str) -> str:
        """Which layer the value comes from, for the report."""
        for layer in self.layers:
            if key in layer.values:
                return layer.name
        d = self.definitions[key]
        return "definition formula" if "value" in d else "definition default"

    def _compute(self, key: str) -> Any:
        for layer in self.layers:
            if key in layer.values:
                raw = layer.values[key]
                if raw.startswith("="):
                    return self.evaluator.evaluate(raw[1:])
                return self.convert(key, raw)
        d = self.definitions[key]
        if "value" in d:
            v = d["value"]
            return self.evaluator.evaluate(v) if isinstance(v, str) else v
        return d.get("default_value")

    def resolve_or_value(self, key: str) -> Any:
        d = self.definitions.get(key, {})
        if "resolve" in d and not any(key in layer.values for layer in self.layers):
            return self.evaluator.evaluate(d["resolve"])
        return self.get(key)

    def all_values(self) -> dict[str, Any]:
        return {key: self.get(key) for key in self.definitions}


# --------------------------------------------------------------------------
# Cura's pre-slice steps (plugins/CuraEngineBackend/StartSliceJob.py)
# --------------------------------------------------------------------------

_TOKEN = re.compile(r"{(?P<condition>if|else|elif|endif)?\s*(?P<expression>[^{}]*?)\s*(?:,\s*(?P<extruder_nr_expr>[^{}]*))?\s*}(?P<end_of_line>\n?)")
_POST_SLICE = {"filament_cost", "print_time", "filament_amount", "filament_weight", "jobname"}


def expand_gcode(text: str, resolver: Resolver) -> str:
    """Fills in {expression} placeholders, including {if}/{elif}/{else}/{endif}, like GcodeStartEndFormatter."""
    out = []
    state = "outside"  # outside | true | false | done
    pos = 0
    for m in _TOKEN.finditer(text):
        if state in ("outside", "true"):
            out.append(text[pos:m.start()])
        pos = m.end()
        cond, expr, eol = m.group("condition"), m.group("expression"), m.group("end_of_line") or ""
        if expr in _POST_SLICE:
            out.append("{" + expr + "}"); continue
        if cond is None:
            if state in ("outside", "true"):
                out.append(str(resolver.evaluator.evaluate(expr)) + eol)
            continue
        if cond == "if":
            if state != "outside": raise FormulaError("nested conditions in G-code are not supported")
            state = "true" if resolver.evaluator.evaluate(expr) else "false"
        elif cond == "elif":
            if state == "true": state = "done"
            elif state == "false": state = "true" if resolver.evaluator.evaluate(expr) else "false"
        elif cond == "else":
            if state == "true": state = "done"
            elif state == "false": state = "true"
        elif cond == "endif":
            state = "outside"
    if state in ("outside", "true"):
        out.append(text[pos:])
    return "".join(out)


def apply_start_slice_job(values: dict[str, Any], resolver: Resolver, quality_name: str, material: Material) -> None:
    start = re.sub(r";.+?(\n|$)", "\n", str(values["machine_start_gcode"]))
    if values["material_bed_temp_prepend"]:
        bed = ["material_bed_temperature", "material_bed_temperature_layer_0"]
        values["material_bed_temp_prepend"] = re.search(r"\{(%s)(,\s?\w+)?\}" % "|".join(bed), start) is None
    if values["material_print_temp_prepend"]:
        hot = ["material_print_temperature", "material_print_temperature_layer_0", "default_material_print_temperature",
               "material_initial_print_temperature", "material_final_print_temperature",
               "material_standby_temperature", "print_temperature"]
        values["material_print_temp_prepend"] = re.search(r"\{(%s)(,\s?\w+)?\}" % "|".join(hot), start) is None
    # Extra tokens Cura offers to start/end G-code.
    resolver.extra.update({
        "material_id": "generic_pla_175",
        "material_type": material.metadata.get("material", ""),
        "material_name": f"{material.metadata.get('brand', '')} {material.metadata.get('material', '')}".strip(),
        "material_brand": material.metadata.get("brand", ""),
        "quality_name": quality_name,
        "print_bed_temperature": values["material_bed_temperature"],
        "print_temperature": values["material_print_temperature"],
        "travel_speed": values["speed_travel"],
        "initial_extruder_nr": 0,
    })
    values["machine_start_gcode"] = expand_gcode(str(values["machine_start_gcode"]), resolver)
    values["machine_end_gcode"] = expand_gcode(str(values["machine_end_gcode"]), resolver)


# --------------------------------------------------------------------------
# Presets
# --------------------------------------------------------------------------

def to_engine_string(v: Any) -> str:
    """Formats a value the way Cura sends it to the engine: str(value)."""
    return str(v)


@dataclass
class Preset:
    id: str
    quality_file: str           # global quality, e.g. elegoo_n4_layer_0.20.inst.cfg
    material_quality_file: str  # e.g. pla/nozzle_0_40/elegoo_n4_pla_nozzle_0.40_layer_0.20.inst.cfg


MACHINE = "elegoo_neptune_4pro"
EXTRUDER = "elegoo_extruder_0"
VARIANT = "variants/elegoo/elegoo_neptune_4pro/elegoo_neptune_4pro_0.40.inst.cfg"
MATERIAL = "generic_pla_175.xml.fdm_material"
QUALITY_DIR = "quality/elegoo/neptune_4"
PRESETS = [
    Preset("fine", "elegoo_n4_layer_0.10.inst.cfg", "pla/nozzle_0_40/elegoo_n4_pla_nozzle_0.40_layer_0.10.inst.cfg"),
    Preset("normal", "elegoo_n4_layer_0.15.inst.cfg", "pla/nozzle_0_40/elegoo_n4_pla_nozzle_0.40_layer_0.15.inst.cfg"),
    Preset("fast", "elegoo_n4_layer_0.20.inst.cfg", "pla/nozzle_0_40/elegoo_n4_pla_nozzle_0.40_layer_0.20.inst.cfg"),
]


def build_preset(src: Path, preset: Preset) -> dict[str, Any]:
    res = src / "cura" / "resources"
    machine_chain, machine_defs = load_definition_chain(res / "definitions", MACHINE)
    _, extruder_defs = load_definition_chain_extruder(res, EXTRUDER)
    # One extruder, one context: extruder-only settings join the machine's.
    definitions = {**extruder_defs, **machine_defs}
    for key, props in extruder_defs.items():
        if key in machine_defs:
            # The extruder definition's overrides (e.g. material_diameter) sit above the machine's.
            ext_overrides = json.loads((res / "extruders" / f"{EXTRUDER}.def.json").read_text())["overrides"]
            if key in ext_overrides:
                definitions[key] = {**machine_defs[key], **ext_overrides[key]}

    _, _, variant_values = read_cfg(res / VARIANT)
    material = read_material(src / "fdm_materials" / MATERIAL, machine_chain)
    q_general, q_meta, q_values = read_cfg(res / QUALITY_DIR / preset.quality_file)
    _, mq_meta, mq_values = read_cfg(res / QUALITY_DIR / preset.material_quality_file)
    if mq_meta.get("quality_type") != q_meta.get("quality_type"):
        raise SystemExit(f"{preset.id}: quality types don't match")

    layers = [  # highest priority first, like a Cura extruder stack
        Layer(f"quality {Path(preset.material_quality_file).name}", mq_values),
        Layer(f"quality {preset.quality_file}", q_values),
        material.layer,
        Layer(f"variant {Path(VARIANT).name}", variant_values),
    ]
    resolver = Resolver(definitions, layers)
    values = resolver.all_values()
    apply_start_slice_job(values, resolver, q_general.get("name", preset.id), material)

    quality_name = q_general.get("name", preset.id)
    layer_height = values["layer_height"]
    return {
        "id": f"{MACHINE}-pla-{preset.id}",
        "name": f"{quality_name} · {layer_height:g} mm",
        "quality": quality_name,
        "machine": str(values["machine_name"]),
        "material": f"{material.metadata.get('brand', '')} {material.metadata.get('material', '')}".strip(),
        "material_density": float(material.metadata.get("density", "1.24")),
        "generated_from": {
            "cura": "5.13.0",
            "fdm_materials": "5.13.0",
            "definitions": machine_chain + [EXTRUDER],
            "variant": Path(VARIANT).name,
            "material": MATERIAL,
            "quality": [preset.quality_file, Path(preset.material_quality_file).name],
        },
        "settings": {k: to_engine_string(values[k]) for k in sorted(values)},
    }


def load_definition_chain_extruder(res: Path, extruder: str) -> tuple[list[str], dict[str, dict[str, Any]]]:
    """Extruder definitions live in resources/extruders and inherit fdmextruder from resources/definitions."""
    data = json.loads((res / "extruders" / f"{extruder}.def.json").read_text(encoding="utf-8"))
    names, settings = load_definition_chain(res / "definitions", data["inherits"])
    for key, props in data.get("overrides", {}).items():
        settings.setdefault(key, {}).update(props)
    return names + [extruder], settings


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--src", type=Path, default=Path.home() / "mobileslicer-build" / "src")
    ap.add_argument("--out", type=Path, default=REPO / "android" / "engine" / "src" / "main" / "assets" / "profiles")
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    for preset in PRESETS:
        profile = build_preset(args.src, preset)
        path = args.out / f"{profile['id']}.json"
        path.write_text(json.dumps(profile, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
        s = profile["settings"]
        print(f"{path.name}: {len(s)} settings · layer {s['layer_height']} mm · "
              f"nozzle {s['material_print_temperature']}/{s['material_print_temperature_layer_0']} C · "
              f"bed {s['material_bed_temperature']} C · speed {s['speed_print']} mm/s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
