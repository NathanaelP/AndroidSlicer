#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Render the viewport's scenes on a desktop, to check shaders and camera math.

Takes the matrices the app computes (app/build/viewport-scenes.json, written by
ViewportScenesTest) and the GLSL shaders straight from ViewportRenderer.kt, and
draws each scene with WebGL2 (the same GLSL ES 3.00) in headless Chromium via
Playwright. Bed, grid and volume geometry mirror ViewportRenderer.syncGeometry.

Usage: android/tools/render_preview.py OUT_DIR
Needs: node with Playwright and Chromium (preinstalled in the cloud container).
"""
import base64
import json
import math
import re
import struct
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RENDERER = ROOT / "app/src/main/java/org/mobileslicer/app/viewport/ViewportRenderer.kt"


def shaders() -> dict:
    src = RENDERER.read_text(encoding="utf-8")
    return {name: body for name, _, body in re.findall(r'const val (\w+_(VS|FS)) = """(.*?)"""', src, re.S)}


def stl_positions(path: str) -> list:
    """Vertex positions of a binary STL, in file order (the order the app uploads them)."""
    data = Path(path).read_bytes()
    n = struct.unpack_from("<I", data, 80)[0]
    out = []
    for t in range(n):
        out += struct.unpack_from("<9f", data, 84 + 50 * t + 12)
    return out


def tris_to_b64(tris: list) -> str:
    return base64.b64encode(struct.pack(f"<{len(tris)}f", *tris)).decode()


def box(x0, y0, z0, x1, y1, z1) -> list:
    v = [(x, y, z) for x in (x0, x1) for y in (y0, y1) for z in (z0, z1)]
    quads = [(0, 1, 3, 2), (4, 6, 7, 5), (0, 4, 5, 1), (2, 3, 7, 6), (0, 2, 6, 4), (1, 5, 7, 3)]
    out = []
    for a, b, c, d in quads:
        for t in ((a, b, c), (a, c, d)):
            for i in t:
                out += v[i]
    return out


def sphere(r, lat=96, lon=192) -> list:
    pts = lambda i, j: (r * math.sin(math.pi * i / lat) * math.cos(2 * math.pi * j / lon),
                        r * math.sin(math.pi * i / lat) * math.sin(2 * math.pi * j / lon),
                        r * math.cos(math.pi * i / lat))
    out = []
    for i in range(lat):
        for j in range(lon):
            a, b, c, d = pts(i, j), pts(i + 1, j), pts(i + 1, j + 1), pts(i, j + 1)
            out += [*a, *b, *c, *a, *c, *d]
    return out


PAGE = """<!doctype html><html><body style="margin:0;background:#000">
<canvas id="c" width="1200" height="800"></canvas>
<script>
const S = %(shaders)s, scene = %(scene)s, meshB64 = %(mesh)s, pathB64 = %(paths)s;
const gl = document.getElementById('c').getContext('webgl2', {antialias: true});
function prog(vs, fs) {
  const mk = (t, s) => { const sh = gl.createShader(t); gl.shaderSource(sh, s); gl.compileShader(sh);
    if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(sh)); return sh; };
  const p = gl.createProgram(); gl.attachShader(p, mk(gl.VERTEX_SHADER, vs)); gl.attachShader(p, mk(gl.FRAGMENT_SHADER, fs));
  gl.bindAttribLocation(p, 0, 'aPos'); gl.linkProgram(p);
  if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(p)); return p;
}
function buf(arr) { const b = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, b); gl.bufferData(gl.ARRAY_BUFFER, arr, gl.STATIC_DRAW); return b; }
function draw(b, mode, first, count) { gl.bindBuffer(gl.ARRAY_BUFFER, b); gl.enableVertexAttribArray(0);
  gl.vertexAttribPointer(0, 3, gl.FLOAT, false, 12, 0); gl.drawArrays(mode, first, count); }
function mul(a, b) { const r = new Float32Array(16);
  for (let c = 0; c < 4; c++) for (let row = 0; row < 4; row++) { let s = 0; for (let k = 0; k < 4; k++) s += a[k*4+row]*b[c*4+k]; r[c*4+row] = s; } return r; }
try {
  const [w, d, h] = scene.bed;
  const model = prog(S.MODEL_VS, S.MODEL_FS), flat = prog(S.FLAT_VS, S.FLAT_FS);
  gl.enable(gl.DEPTH_TEST); gl.depthFunc(gl.LEQUAL);
  gl.clearColor(0.11, 0.11, 0.13, 1); gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
  const z = -0.2; const bed = buf(new Float32Array([0,0,z, w,0,z, w,d,z, 0,0,z, w,d,z, 0,d,z]));
  const minor = [], major = [];
  for (let x = 0; x <= w + 0.01; x += 10) (Math.trunc(x) %% 50 === 0 ? major : minor).push(x,0,0, x,d,0);
  for (let y = 0; y <= d + 0.01; y += 10) (Math.trunc(y) %% 50 === 0 ? major : minor).push(0,y,0, w,y,0);
  const grid = buf(new Float32Array(minor.concat(major)));
  const c = [[0,0],[w,0],[w,d],[0,d]], e = [];
  for (let i = 0; i < 4; i++) { const a = c[i], b = c[(i+1)%%4]; e.push(a[0],a[1],0,b[0],b[1],0, a[0],a[1],h,b[0],b[1],h, a[0],a[1],0,a[0],a[1],h); }
  const vol = buf(new Float32Array(e));
  const vp = new Float32Array(scene.viewProj);
  gl.useProgram(flat);
  const uMvp = gl.getUniformLocation(flat, 'uMvp'), uColor = gl.getUniformLocation(flat, 'uColor');
  gl.uniformMatrix4fv(uMvp, false, vp);
  gl.uniform4f(uColor, 0.20, 0.21, 0.23, 1); draw(bed, gl.TRIANGLES, 0, 6);
  gl.uniform4f(uColor, 0.30, 0.31, 0.34, 1); draw(grid, gl.LINES, 0, minor.length / 3);
  gl.uniform4f(uColor, 0.42, 0.44, 0.48, 1); draw(grid, gl.LINES, minor.length / 3, major.length / 3);
  gl.uniform4f(uColor, 0.45, 0.62, 0.85, 1); draw(vol, gl.LINES, 0, 24);
  const colors = {amber: [0.96, 0.74, 0.30], selected: [0.45, 0.72, 1.0], red: [0.90, 0.36, 0.30],
                  floating: [0.74, 0.42, 0.95], floating_selected: [0.86, 0.62, 1.0]};
  const bufs = {};
  for (const [name, b64] of Object.entries(meshB64)) {
    const bytes = Uint8Array.from(atob(b64), ch => ch.charCodeAt(0));
    bufs[name] = [buf(new Float32Array(bytes.buffer)), bytes.length / 12];
  }
  gl.useProgram(model);
  const lineItems = [];
  for (const it of scene.items) {
    if (it.lines && it.lines.length) lineItems.push(it);
    const m = new Float32Array(it.model);
    gl.uniformMatrix4fv(gl.getUniformLocation(model, 'uMvp'), false, mul(vp, m));
    gl.uniformMatrix4fv(gl.getUniformLocation(model, 'uModelView'), false, mul(new Float32Array(scene.view), m));
    const [b] = bufs[it.mesh];
    for (const [first, count, color] of it.segments) {
      gl.uniform3f(gl.getUniformLocation(model, 'uColor'), ...colors[color]);
      draw(b, gl.TRIANGLES, first * 3, count * 3);
    }
  }
  // Toolpaths, as ViewportRenderer.drawPreview draws them.
  if (scene.preview) {
    const pv = scene.preview;
    const bytes = (b64) => { const u = Uint8Array.from(atob(b64), ch => ch.charCodeAt(0)); return u; };
    const path = prog(S.PATH_VS, S.PATH_FS);
    gl.bindAttribLocation(path, 0, 'aCorner');
    gl.useProgram(path);
    gl.uniformMatrix4fv(gl.getUniformLocation(path, 'uMvp'), false, vp);
    gl.uniformMatrix4fv(gl.getUniformLocation(path, 'uView'), false, new Float32Array(scene.view));
    gl.uniform3fv(gl.getUniformLocation(path, 'uColors'), new Float32Array(pv.colors));
    gl.uniform1fv(gl.getUniformLocation(path, 'uVisible'), new Float32Array(pv.visible));
    gl.uniform1f(gl.getUniformLocation(path, 'uTopZ'), pv.topZ);
    const bead = buf(new Float32Array(pv.bead));
    gl.bindBuffer(gl.ARRAY_BUFFER, bead); gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 3, gl.FLOAT, false, 12, 0);
    const seg = buf(bytes(pathB64.segments));
    const base = pv.first * 32;
    gl.bindBuffer(gl.ARRAY_BUFFER, seg);
    gl.enableVertexAttribArray(1); gl.vertexAttribPointer(1, 4, gl.FLOAT, false, 32, base); gl.vertexAttribDivisor(1, 1);
    gl.enableVertexAttribArray(2); gl.vertexAttribPointer(2, 4, gl.FLOAT, false, 32, base + 16); gl.vertexAttribDivisor(2, 1);
    gl.drawArraysInstanced(gl.TRIANGLES, 0, pv.bead.length / 3, pv.count);
    gl.vertexAttribDivisor(1, 0); gl.vertexAttribDivisor(2, 0); gl.disableVertexAttribArray(1); gl.disableVertexAttribArray(2);
    if (pv.travelCount > 0) {
      gl.useProgram(flat);
      gl.uniformMatrix4fv(uMvp, false, vp);
      gl.uniform4f(uColor, pv.travelColor[0], pv.travelColor[1], pv.travelColor[2], 1);
      draw(buf(bytes(pathB64.travels)), gl.LINES, 2 * pv.travelFirst, 2 * pv.travelCount);
    }
  }
  // Hole outlines over everything, as ViewportRenderer draws them.
  gl.useProgram(flat); gl.disable(gl.DEPTH_TEST); gl.lineWidth(3);
  for (const it of lineItems) {
    gl.uniformMatrix4fv(uMvp, false, mul(vp, new Float32Array(it.model)));
    gl.uniform4f(uColor, 0.30, 1.0, 0.45, 1);
    draw(buf(new Float32Array(it.lines)), gl.LINES, 0, it.lines.length / 3);
  }
  gl.enable(gl.DEPTH_TEST);
  document.title = 'ok';
} catch (err) { document.title = 'error: ' + err.message; }
</script></body></html>"""

NODE = """
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch({ args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
  const p = await b.newPage({ viewport: { width: 1200, height: 800 } });
  for (const [html, png] of JSON.parse(process.argv[2])) {
    await p.goto('file://' + html);
    await p.waitForFunction(() => document.title !== '');
    const t = await p.title();
    console.log(png + ': ' + t);
    await p.screenshot({ path: png });
  }
  await b.close();
})();
"""


def main() -> int:
    out = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    out.mkdir(parents=True, exist_ok=True)
    scenes = json.loads((ROOT / "app/build/viewport-scenes.json").read_text())
    jobs = []
    with tempfile.TemporaryDirectory() as tmp:
        for sc in scenes:
            html = Path(tmp) / f"{sc['name']}.html"
            used = {it["mesh"] for it in sc["items"]}
            mesh = json.dumps({path: tris_to_b64(stl_positions(path)) for path in used})
            paths = {}
            if "preview" in sc:
                for k in ("segments", "travels"):
                    paths[k] = base64.b64encode(Path(sc["preview"][k]).read_bytes()).decode()
            html.write_text(PAGE % {"shaders": json.dumps(shaders()), "scene": json.dumps(sc), "mesh": mesh, "paths": json.dumps(paths)})
            jobs.append([str(html), str(out / f"{sc['name']}.png")])
        js = Path(tmp) / "render.js"
        js.write_text(NODE)
        r = subprocess.run(["node", str(js), json.dumps(jobs)], capture_output=True, text=True)
        print(r.stdout + r.stderr)
        return r.returncode


if __name__ == "__main__":
    sys.exit(main())
