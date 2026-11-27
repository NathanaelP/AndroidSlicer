// SPDX-License-Identifier: AGPL-3.0-or-later
package org.mobileslicer.app.viewport

import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.util.Log
import org.mobileslicer.engine.GcodePreview
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * A mesh file the renderer may draw; [key] identifies it (duplicated objects share
 * one). [outlines] are line segments (x, y, z pairs, mesh coordinates) drawn over
 * it, such as hole rims.
 */
class MeshRef(val key: String, val file: File, val triangles: Long, val outlines: FloatArray = FloatArray(0))

/** A run of triangles in one colour: triangles [first] until [first] + [count]. */
data class Segment(val first: Int, val count: Int, val color: FloatArray)

/**
 * One object to draw: which mesh, where (column-major model matrix), and its
 * colours as runs of triangles. Parts are contiguous in the mesh, so a part can
 * have its own colour.
 */
data class DrawItem(
    val meshKey: String,
    val matrix: FloatArray,
    val segments: List<Segment>,
    /** Colour for the mesh's outlines, drawn on top of everything; null to leave them out. */
    val outlineColor: FloatArray? = null,
)

/**
 * Sliced toolpaths to draw in place of the models: layers [fromLayer] to
 * [toLayer] of [data], with [visible] per MoveType and travel moves if [travel].
 */
class PreviewDraw(
    val data: GcodePreview,
    val fromLayer: Int,
    val toLayer: Int,
    val visible: BooleanArray,
    val travel: Boolean,
)

/** Everything the renderer draws. Replaced as a whole from the UI thread. */
data class SceneState(
    val camera: OrbitCamera,
    val bedWidth: Float,
    val bedDepth: Float,
    val bedHeight: Float,
    val items: List<DrawItem>,
    val background: FloatArray,
    /** When set, the toolpaths are drawn instead of [items]. */
    val preview: PreviewDraw? = null,
)

/** Frame statistics reported back to the UI. */
data class RenderStats(val triangles: Long, val framesPerSecond: Float?, val uploadMillis: Long?, val error: String?)

/**
 * OpenGL ES 3.0 renderer for the viewport: bed, grid, build volume and the model.
 *
 * The model is uploaded once from the importer's binary STL, positions only
 * (36 bytes per triangle). The fragment shader works out flat-shading normals
 * from screen-space derivatives, so no per-vertex normals are stored. That halves
 * GPU memory for dense sculpts, and lighting is two-sided so meshes with flipped
 * faces still read well.
 */
class ViewportRenderer(private val onStats: (RenderStats) -> Unit) : GLSurfaceView.Renderer {

    @Volatile var scene: SceneState? = null

    // Meshes to keep on the GPU; read on the GL thread.
    @Volatile private var meshes: List<MeshRef> = emptyList()
    private class Gpu(val vbo: Int, val triangles: Long, val lineVbo: Int = 0, val lineVertices: Int = 0)
    private val uploaded = HashMap<String, Gpu>()

    private var width = 1
    private var height = 1
    private var modelProgram = 0
    private var flatProgram = 0
    private var pathProgram = 0
    private var beadVbo = 0
    // Toolpaths on the GPU, for the preview they came from.
    private var pathFor: GcodePreview? = null
    private var segVbo = 0
    private var travelVbo = 0
    private var bedVbo = 0
    private var gridVbo = 0
    private var gridMinorCount = 0
    private var gridMajorCount = 0
    private var volumeVbo = 0
    private var geometryFor: Triple<Float, Float, Float>? = null
    private var lastError: String? = null
    private var lastUploadMillis: Long? = null

    // Frame rate while frames come back to back (during gestures).
    private var lastFrameNanos = 0L
    private val frameIntervals = ArrayDeque<Long>()
    private var lastStatsNanos = 0L

    /** The meshes the scene uses (binary STLs from MeshImporter). Thread-safe. */
    fun setMeshes(refs: List<MeshRef>) {
        meshes = refs
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A new context: everything on the GPU is gone.
        modelProgram = program(MODEL_VS, MODEL_FS)
        flatProgram = program(FLAT_VS, FLAT_FS)
        pathProgram = program(PATH_VS, PATH_FS)
        beadVbo = buffer(BEAD)
        pathFor = null; segVbo = 0; travelVbo = 0
        bedVbo = 0; gridVbo = 0; volumeVbo = 0
        uploaded.clear(); geometryFor = null
        glEnable(GL_DEPTH_TEST)
        glDepthFunc(GL_LEQUAL)
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = maxOf(1, w); height = maxOf(1, h)
        glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val s = scene
        val bg = s?.background ?: floatArrayOf(0.1f, 0.1f, 0.1f, 1f)
        glClearColor(bg[0], bg[1], bg[2], 1f)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        if (s == null) return

        syncMesh()
        syncGeometry(s.bedWidth, s.bedDepth, s.bedHeight)

        val viewProj = Mat4.mul(s.camera.projection(width.toFloat() / height), s.camera.view())

        // Bed: a filled plate just below Z = 0 (see BED_Z), then the grid on Z = 0.
        glUseProgram(flatProgram)
        val uMvp = glGetUniformLocation(flatProgram, "uMvp")
        val uColor = glGetUniformLocation(flatProgram, "uColor")
        glUniformMatrix4fv(uMvp, 1, false, viewProj, 0)
        glUniform4f(uColor, 0.20f, 0.21f, 0.23f, 1f)
        drawArrays(bedVbo, GL_TRIANGLES, 0, 6)
        glUniform4f(uColor, 0.30f, 0.31f, 0.34f, 1f)
        drawArrays(gridVbo, GL_LINES, 0, gridMinorCount)
        glUniform4f(uColor, 0.42f, 0.44f, 0.48f, 1f)
        drawArrays(gridVbo, GL_LINES, gridMinorCount, gridMajorCount)
        glUniform4f(uColor, 0.45f, 0.62f, 0.85f, 1f)
        drawArrays(volumeVbo, GL_LINES, 0, 24)

        val preview = s.preview
        if (preview != null) drawPreview(preview, viewProj, s.camera.view())

        // Models.
        if (preview == null && s.items.isNotEmpty()) {
            glUseProgram(modelProgram)
            val uMvp2 = glGetUniformLocation(modelProgram, "uMvp")
            val uModelView = glGetUniformLocation(modelProgram, "uModelView")
            val uColor2 = glGetUniformLocation(modelProgram, "uColor")
            val view = s.camera.view()
            for (item in s.items) {
                val gpu = uploaded[item.meshKey] ?: continue
                if (gpu.vbo == 0 || gpu.triangles <= 0) continue
                glUniformMatrix4fv(uMvp2, 1, false, Mat4.mul(viewProj, item.matrix), 0)
                glUniformMatrix4fv(uModelView, 1, false, Mat4.mul(view, item.matrix), 0)
                for (seg in item.segments) {
                    val first = seg.first.toLong().coerceIn(0, gpu.triangles)
                    val count = seg.count.toLong().coerceIn(0, gpu.triangles - first)
                    if (count == 0L) continue
                    val c = seg.color
                    glUniform3f(uColor2, c[0], c[1], c[2])
                    drawArrays(gpu.vbo, GL_TRIANGLES, (first * 3).toInt(), (count * 3).toInt())
                }
            }
        }
        // Outlines (hole rims) over everything, so holes on the far side show too.
        if (preview == null && s.items.any { it.outlineColor != null }) {
            glUseProgram(flatProgram)
            glDisable(GL_DEPTH_TEST)
            glLineWidth(3f)
            for (item in s.items) {
                val c = item.outlineColor ?: continue
                val gpu = uploaded[item.meshKey] ?: continue
                if (gpu.lineVbo == 0) continue
                glUniformMatrix4fv(uMvp, 1, false, Mat4.mul(viewProj, item.matrix), 0)
                glUniform4f(uColor, c[0], c[1], c[2], 1f)
                drawArrays(gpu.lineVbo, GL_LINES, 0, gpu.lineVertices)
            }
            glLineWidth(1f)
            glEnable(GL_DEPTH_TEST)
        }
        reportFrame()
    }

    /** The sliced toolpaths: one instance of the bead template per extrusion move. */
    private fun drawPreview(p: PreviewDraw, viewProj: FloatArray, view: FloatArray) {
        val data = p.data
        if (pathFor !== data) {
            for (vbo in intArrayOf(segVbo, travelVbo)) if (vbo != 0) glDeleteBuffers(1, intArrayOf(vbo), 0)
            segVbo = upload(data.segments)
            travelVbo = upload(data.travels)
            pathFor = data
        }
        if (data.layers.isEmpty()) return
        val from = p.fromLayer.coerceIn(0, data.layers.lastIndex)
        val to = p.toLayer.coerceIn(from, data.layers.lastIndex)
        val first = data.layers[from].firstSegment
        val count = data.layers[to].let { it.firstSegment + it.segmentCount } - first

        if (count > 0 && segVbo != 0) {
            glUseProgram(pathProgram)
            glUniformMatrix4fv(glGetUniformLocation(pathProgram, "uMvp"), 1, false, viewProj, 0)
            glUniformMatrix4fv(glGetUniformLocation(pathProgram, "uView"), 1, false, view, 0)
            glUniform3fv(glGetUniformLocation(pathProgram, "uColors"), PATH_COLORS.size / 3, PATH_COLORS, 0)
            glUniform1fv(glGetUniformLocation(pathProgram, "uVisible"), p.visible.size, FloatArray(p.visible.size) { if (p.visible[it]) 1f else 0f }, 0)
            glUniform1f(glGetUniformLocation(pathProgram, "uTopZ"), data.layers[to].z)
            glBindBuffer(GL_ARRAY_BUFFER, beadVbo)
            glEnableVertexAttribArray(0)
            glVertexAttribPointer(0, 3, GL_FLOAT, false, 12, 0)
            glBindBuffer(GL_ARRAY_BUFFER, segVbo)
            // Instance attributes start at the first visible layer's first move (ES 3.0 has no base instance).
            val base = first * GcodePreview.SEGMENT_BYTES
            glEnableVertexAttribArray(1)
            glVertexAttribPointer(1, 4, GL_FLOAT, false, GcodePreview.SEGMENT_BYTES, base)
            glVertexAttribDivisor(1, 1)
            glEnableVertexAttribArray(2)
            glVertexAttribPointer(2, 4, GL_FLOAT, false, GcodePreview.SEGMENT_BYTES, base + 16)
            glVertexAttribDivisor(2, 1)
            glDrawArraysInstanced(GL_TRIANGLES, 0, BEAD.size / 3, count)
            glVertexAttribDivisor(1, 0); glVertexAttribDivisor(2, 0)
            glDisableVertexAttribArray(1); glDisableVertexAttribArray(2); glDisableVertexAttribArray(0)
            glBindBuffer(GL_ARRAY_BUFFER, 0)
        }
        if (p.travel && travelVbo != 0) {
            val t0 = data.layers[from].firstTravel
            val tn = data.layers[to].let { it.firstTravel + it.travelCount } - t0
            glUseProgram(flatProgram)
            glUniformMatrix4fv(glGetUniformLocation(flatProgram, "uMvp"), 1, false, viewProj, 0)
            glUniform4f(glGetUniformLocation(flatProgram, "uColor"), TRAVEL_COLOR[0], TRAVEL_COLOR[1], TRAVEL_COLOR[2], 1f)
            drawArrays(travelVbo, GL_LINES, 2 * t0, 2 * tn)
        }
    }

    private fun upload(data: java.nio.ByteBuffer): Int {
        if (data.limit() == 0) return 0
        val ids = IntArray(1)
        glGenBuffers(1, ids, 0)
        glBindBuffer(GL_ARRAY_BUFFER, ids[0])
        glBufferData(GL_ARRAY_BUFFER, data.limit(), data.duplicate().position(0), GL_STATIC_DRAW)
        glBindBuffer(GL_ARRAY_BUFFER, 0)
        if (glGetError() == GL_OUT_OF_MEMORY) {
            glDeleteBuffers(1, ids, 0)
            lastError = "Not enough graphics memory for the preview"
            return 0
        }
        return ids[0]
    }

    private fun drawArrays(vbo: Int, mode: Int, first: Int, count: Int) {
        if (vbo == 0 || count <= 0) return
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 12, 0)
        glDrawArrays(mode, first, count)
        glDisableVertexAttribArray(0)
        glBindBuffer(GL_ARRAY_BUFFER, 0)
    }

    /** Uploads meshes the scene gained and frees those it no longer uses. */
    private fun syncMesh() {
        val wanted = meshes
        val keys = wanted.map { it.key }.toSet()
        for (key in uploaded.keys - keys) {
            uploaded.remove(key)?.let { g ->
                if (g.vbo != 0) glDeleteBuffers(1, intArrayOf(g.vbo), 0)
                if (g.lineVbo != 0) glDeleteBuffers(1, intArrayOf(g.lineVbo), 0)
            }
            publish()
        }
        for (ref in wanted) {
            if (ref.key in uploaded) continue
            val start = SystemClock.elapsedRealtime()
            try {
                val (vbo, tris) = uploadStl(ref.file, ref.triangles)
                val lines = if (ref.outlines.isNotEmpty()) buffer(ref.outlines) else 0
                uploaded[ref.key] = Gpu(vbo, tris, lines, ref.outlines.size / 3)
                lastUploadMillis = SystemClock.elapsedRealtime() - start
                lastError = null
            } catch (e: Exception) {
                Log.w(TAG, "mesh upload failed", e)
                lastError = e.message ?: e.javaClass.simpleName
                uploaded[ref.key] = Gpu(0, 0)  // don't retry every frame
            }
            publish()
        }
    }

    /** Streams triangle positions from a binary STL into a vertex buffer, in chunks. */
    private fun uploadStl(file: File, expected: Long): Pair<Int, Long> {
        val triangles = minOf(expected, (file.length() - 84) / 50)
        if (triangles <= 0) return 0 to 0L
        val bytes = triangles * 36
        if (bytes > Int.MAX_VALUE) throw IllegalStateException("Model too large to display")
        val ids = IntArray(1)
        glGenBuffers(1, ids, 0)
        val vbo = ids[0]
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glBufferData(GL_ARRAY_BUFFER, bytes.toInt(), null, GL_STATIC_DRAW)
        if (glGetError() == GL_OUT_OF_MEMORY) {
            glDeleteBuffers(1, ids, 0)
            throw IllegalStateException("Not enough graphics memory for this model")
        }

        var done = 0L
        // Free the buffer if reading fails partway.
        try {
            val chunk = 32768
            val record = ByteArray(50 * chunk)
            val inBuf = ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN)
            val out = ByteBuffer.allocateDirect(36 * chunk).order(ByteOrder.nativeOrder())
            val outFloats: FloatBuffer = out.asFloatBuffer()
            BufferedInputStream(FileInputStream(file), 1 shl 16).use { input ->
                var skip = 84L
                while (skip > 0) skip -= input.skip(skip).also { if (it <= 0) throw IllegalStateException("Truncated mesh file") }
                while (done < triangles) {
                    val n = minOf(chunk.toLong(), triangles - done).toInt()
                    var read = 0
                    while (read < 50 * n) {
                        val r = input.read(record, read, 50 * n - read)
                        if (r < 0) throw IllegalStateException("Truncated mesh file")
                        read += r
                    }
                    outFloats.clear()
                    for (t in 0 until n) for (i in 0 until 9) outFloats.put(inBuf.getFloat(50 * t + 12 + 4 * i))
                    out.position(0).limit(36 * n)
                    glBufferSubData(GL_ARRAY_BUFFER, (done * 36).toInt(), 36 * n, out)
                    done += n
                }
            }
        } catch (e: Exception) {
            glBindBuffer(GL_ARRAY_BUFFER, 0)
            glDeleteBuffers(1, ids, 0)
            throw e
        }
        glBindBuffer(GL_ARRAY_BUFFER, 0)
        return vbo to done
    }

    /** Bed plate, grid and build-volume outline for the current bed size. */
    private fun syncGeometry(w: Float, d: Float, h: Float) {
        if (geometryFor == Triple(w, d, h)) return
        geometryFor = Triple(w, d, h)
        for (vbo in intArrayOf(bedVbo, gridVbo, volumeVbo)) if (vbo != 0) glDeleteBuffers(1, intArrayOf(vbo), 0)
        val z = BED_Z
        bedVbo = buffer(floatArrayOf(0f, 0f, z, w, 0f, z, w, d, z, 0f, 0f, z, w, d, z, 0f, d, z))

        val minor = ArrayList<Float>(); val major = ArrayList<Float>()
        fun line(list: MutableList<Float>, x0: Float, y0: Float, x1: Float, y1: Float) { list += listOf(x0, y0, 0f, x1, y1, 0f) }
        var x = 0f
        while (x <= w + 0.01f) { line(if (x.toInt() % 50 == 0) major else minor, x, 0f, x, d); x += 10f }
        var y = 0f
        while (y <= d + 0.01f) { line(if (y.toInt() % 50 == 0) major else minor, 0f, y, w, y); y += 10f }
        gridMinorCount = minor.size / 3
        gridMajorCount = major.size / 3
        gridVbo = buffer((minor + major).toFloatArray())

        val c = listOf(Vec3(0f, 0f, 0f), Vec3(w, 0f, 0f), Vec3(w, d, 0f), Vec3(0f, d, 0f))
        val edges = ArrayList<Float>()
        for (i in 0 until 4) {
            val a = c[i]; val b = c[(i + 1) % 4]
            edges += listOf(a.x, a.y, 0f, b.x, b.y, 0f)  // bottom
            edges += listOf(a.x, a.y, h, b.x, b.y, h)    // top
            edges += listOf(a.x, a.y, 0f, a.x, a.y, h)   // upright
        }
        volumeVbo = buffer(edges.toFloatArray())
    }

    private fun buffer(data: FloatArray): Int {
        val ids = IntArray(1)
        glGenBuffers(1, ids, 0)
        val fb = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(data)
        fb.position(0)
        glBindBuffer(GL_ARRAY_BUFFER, ids[0])
        glBufferData(GL_ARRAY_BUFFER, data.size * 4, fb, GL_STATIC_DRAW)
        glBindBuffer(GL_ARRAY_BUFFER, 0)
        return ids[0]
    }

    private fun reportFrame() {
        val now = System.nanoTime()
        val gap = now - lastFrameNanos
        lastFrameNanos = now
        // Only count frames that follow each other closely, i.e. while the view is moving.
        if (gap in 1 until 250_000_000L) {
            frameIntervals.addLast(gap)
            while (frameIntervals.size > 60) frameIntervals.removeFirst()
        }
        if (now - lastStatsNanos > 500_000_000L) {
            lastStatsNanos = now
            publish()
        }
    }

    private fun publish() {
        val fps = if (frameIntervals.size >= 5) 1e9f / (frameIntervals.sum().toFloat() / frameIntervals.size) else null
        val drawn = scene?.items?.sumOf { uploaded[it.meshKey]?.triangles ?: 0L } ?: 0L
        onStats(RenderStats(drawn, fps, lastUploadMillis, lastError))
    }

    private fun program(vs: String, fs: String): Int {
        fun shader(type: Int, src: String): Int {
            val s = glCreateShader(type)
            glShaderSource(s, src)
            glCompileShader(s)
            val ok = IntArray(1)
            glGetShaderiv(s, GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) throw IllegalStateException("Shader compile failed: " + glGetShaderInfoLog(s))
            return s
        }
        val p = glCreateProgram()
        glAttachShader(p, shader(GL_VERTEX_SHADER, vs))
        glAttachShader(p, shader(GL_FRAGMENT_SHADER, fs))
        glBindAttribLocation(p, 0, "aPos")
        glLinkProgram(p)
        val ok = IntArray(1)
        glGetProgramiv(p, GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw IllegalStateException("Program link failed: " + glGetProgramInfoLog(p))
        return p
    }

    companion object {
        private const val TAG = "ViewportRenderer"

        /**
         * The bed plate sits this far below Z = 0 (mm). The model rests exactly on
         * Z = 0, so a plate at 0 would fight it for depth. Slightly below, the grid
         * still draws on top from above, and from below the plate hides the model's
         * contact patches instead of letting them show through.
         */
        const val BED_Z = -0.2f

        // Checked by tools/check_shaders.py with Khronos glslangValidator.
        /**
         * The bead template, one triangle list: a diamond cross-section (left edge,
         * top ridge, right edge, bottom) swept along the move. Each corner is
         * (along 0..1, across -1..1, up 0..1).
         */
        val BEAD: FloatArray = run {
            val ring = listOf(-1f to 0.5f, 0f to 1f, 1f to 0.5f, 0f to 0f)
            val out = ArrayList<Float>()
            for (i in ring.indices) {
                val (a, ah) = ring[i]; val (b, bh) = ring[(i + 1) % ring.size]
                val q = listOf(floatArrayOf(0f, a, ah), floatArrayOf(1f, a, ah), floatArrayOf(1f, b, bh), floatArrayOf(0f, b, bh))
                for (k in intArrayOf(0, 1, 2, 0, 2, 3)) out += q[k].toList()
            }
            out.toFloatArray()
        }

        /** Colours per MoveType, in its order; the usual slicer colours. */
        val PATH_COLORS = floatArrayOf(
            0.90f, 0.27f, 0.22f,  // outer wall: red
            0.30f, 0.78f, 0.35f,  // inner walls: green
            0.95f, 0.84f, 0.30f,  // top and bottom: yellow
            0.95f, 0.55f, 0.20f,  // infill: orange
            0.30f, 0.80f, 0.88f,  // support: cyan
            0.40f, 0.72f, 0.66f,  // skirt and brim: teal
            0.72f, 0.72f, 0.78f,  // prime tower: grey
            0.60f, 0.60f, 0.60f,  // other
        )
        val TRAVEL_COLOR = floatArrayOf(0.35f, 0.50f, 0.98f)

        const val PATH_VS = """#version 300 es
uniform mat4 uMvp;
uniform mat4 uView;
uniform vec3 uColors[8];
uniform float uVisible[8];
uniform float uTopZ;
layout(location = 0) in vec3 aCorner;  // along, across, up
layout(location = 1) in vec4 aEnds;    // x0, y0, x1, y1
layout(location = 2) in vec4 aInfo;    // z (top), layer height, width, type
out highp vec3 vViewPos;
out vec3 vColor;
void main() {
    int t = int(aInfo.w + 0.5);
    if (uVisible[t] < 0.5) { gl_Position = vec4(2.0, 2.0, 2.0, 1.0); vViewPos = vec3(0.0); vColor = vec3(0.0); return; }
    vec2 d = aEnds.zw - aEnds.xy;
    float len = length(d);
    vec2 dir = len > 1e-6 ? d / len : vec2(1.0, 0.0);
    vec2 across = vec2(-dir.y, dir.x);
    float halfW = 0.5 * aInfo.z;
    // Lengthen by half a width at both ends so corners join up.
    vec2 xy = mix(aEnds.xy - dir * halfW, aEnds.zw + dir * halfW, aCorner.x) + across * (aCorner.y * halfW);
    float z = aInfo.x - aInfo.y + aCorner.z * aInfo.y;
    vec4 p = vec4(xy, z, 1.0);
    vViewPos = (uView * p).xyz;
    // Layers below the one being looked at are a little darker.
    vColor = uColors[t] * (aInfo.x < uTopZ - 1e-3 ? 0.78 : 1.0);
    gl_Position = uMvp * p;
}
"""

        const val PATH_FS = """#version 300 es
precision highp float;
in highp vec3 vViewPos;
in vec3 vColor;
out vec4 fragColor;
void main() {
    vec3 n = normalize(cross(dFdx(vViewPos), dFdy(vViewPos)));
    vec3 toEye = normalize(-vViewPos);
    if (dot(n, toEye) < 0.0) n = -n;
    vec3 key = normalize(vec3(-0.5, 0.75, 0.45));
    float light = 0.30 + 0.25 * dot(n, toEye) + 0.50 * max(dot(n, key), 0.0);
    fragColor = vec4(vColor * light, 1.0);
}
"""

        const val MODEL_VS = """#version 300 es
uniform mat4 uMvp;
uniform mat4 uModelView;
layout(location = 0) in vec3 aPos;
out highp vec3 vViewPos;
void main() {
    vec4 p = vec4(aPos, 1.0);
    vViewPos = (uModelView * p).xyz;
    gl_Position = uMvp * p;
}
"""
        const val MODEL_FS = """#version 300 es
precision highp float;
in highp vec3 vViewPos;
uniform vec3 uColor;
out vec4 fragColor;
void main() {
    // Flat normal from screen-space derivatives of the view-space position.
    vec3 n = normalize(cross(dFdx(vViewPos), dFdy(vViewPos)));
    vec3 toEye = normalize(-vViewPos);
    // Two-sided: always light the side we see, so flipped faces aren't black.
    if (dot(n, toEye) < 0.0) n = -n;
    // Key light from the upper left of the view, plus a weaker headlight, so
    // faces at different angles get clearly different shades.
    vec3 key = normalize(vec3(-0.5, 0.75, 0.45));
    float diffuse = max(dot(n, key), 0.0);
    float head = dot(n, toEye);
    float light = 0.24 + 0.28 * head + 0.56 * diffuse;
    fragColor = vec4(uColor * light, 1.0);
}
"""
        const val FLAT_VS = """#version 300 es
uniform mat4 uMvp;
layout(location = 0) in vec3 aPos;
void main() {
    gl_Position = uMvp * vec4(aPos, 1.0);
}
"""
        const val FLAT_FS = """#version 300 es
precision mediump float;
uniform vec4 uColor;
out vec4 fragColor;
void main() {
    fragColor = uColor;
}
"""
    }
}
