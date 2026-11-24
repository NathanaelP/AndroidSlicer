// SPDX-License-Identifier: AGPL-3.0-or-later
package org.mobileslicer.app.viewport

import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Everything the renderer draws besides the mesh itself. Replaced as a whole from the UI thread. */
data class SceneState(
    val camera: OrbitCamera,
    val bedWidth: Float,
    val bedDepth: Float,
    val bedHeight: Float,
    /** Model matrix from [modelMatrix], or null to hide the model. */
    val model: FloatArray?,
    val modelColor: FloatArray,
    val background: FloatArray,
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

    // Mesh to show; read on the GL thread.
    @Volatile private var meshFile: File? = null
    @Volatile private var meshTriangles: Long = 0
    @Volatile private var meshVersion: Long = 0
    private var uploadedVersion = -1L

    private var width = 1
    private var height = 1
    private var modelProgram = 0
    private var flatProgram = 0
    private var meshVbo = 0
    private var drawnTriangles = 0L
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

    /**
     * Shows the mesh in [file] (binary STL from MeshImporter), or nothing if null.
     * Re-uploads whenever [version] changes, since imports reuse the same file. Thread-safe.
     */
    fun setMesh(file: File?, triangles: Long, version: Long) {
        meshFile = file
        meshTriangles = triangles
        meshVersion = version
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A new context: everything on the GPU is gone.
        modelProgram = program(MODEL_VS, MODEL_FS)
        flatProgram = program(FLAT_VS, FLAT_FS)
        meshVbo = 0; bedVbo = 0; gridVbo = 0; volumeVbo = 0
        uploadedVersion = -1L; geometryFor = null
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

        // Bed: a filled plate pushed back slightly so the grid and the model's base draw over it.
        glUseProgram(flatProgram)
        val uMvp = glGetUniformLocation(flatProgram, "uMvp")
        val uColor = glGetUniformLocation(flatProgram, "uColor")
        glUniformMatrix4fv(uMvp, 1, false, viewProj, 0)
        glEnable(GL_POLYGON_OFFSET_FILL)
        glPolygonOffset(2f, 2f)
        glUniform4f(uColor, 0.20f, 0.21f, 0.23f, 1f)
        drawArrays(bedVbo, GL_TRIANGLES, 0, 6)
        glDisable(GL_POLYGON_OFFSET_FILL)
        glUniform4f(uColor, 0.30f, 0.31f, 0.34f, 1f)
        drawArrays(gridVbo, GL_LINES, 0, gridMinorCount)
        glUniform4f(uColor, 0.42f, 0.44f, 0.48f, 1f)
        drawArrays(gridVbo, GL_LINES, gridMinorCount, gridMajorCount)
        glUniform4f(uColor, 0.45f, 0.62f, 0.85f, 1f)
        drawArrays(volumeVbo, GL_LINES, 0, 24)

        // Model.
        val model = s.model
        if (model != null && meshVbo != 0 && drawnTriangles > 0) {
            glUseProgram(modelProgram)
            val modelView = Mat4.mul(s.camera.view(), model)
            glUniformMatrix4fv(glGetUniformLocation(modelProgram, "uMvp"), 1, false, Mat4.mul(viewProj, model), 0)
            glUniformMatrix4fv(glGetUniformLocation(modelProgram, "uModelView"), 1, false, modelView, 0)
            val c = s.modelColor
            glUniform3f(glGetUniformLocation(modelProgram, "uColor"), c[0], c[1], c[2])
            drawArrays(meshVbo, GL_TRIANGLES, 0, (drawnTriangles * 3).toInt())
        }
        reportFrame()
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

    /** (Re)uploads the mesh when the file changes. */
    private fun syncMesh() {
        val version = meshVersion
        if (version == uploadedVersion) return
        uploadedVersion = version
        val file = meshFile
        if (meshVbo != 0) { glDeleteBuffers(1, intArrayOf(meshVbo), 0); meshVbo = 0 }
        drawnTriangles = 0
        lastError = null
        if (file == null || !file.exists()) { publish(); return }
        val start = SystemClock.elapsedRealtime()
        try {
            drawnTriangles = uploadStl(file, meshTriangles)
            lastUploadMillis = SystemClock.elapsedRealtime() - start
        } catch (e: Exception) {
            Log.w(TAG, "mesh upload failed", e)
            lastError = e.message ?: e.javaClass.simpleName
            if (meshVbo != 0) { glDeleteBuffers(1, intArrayOf(meshVbo), 0); meshVbo = 0 }
            drawnTriangles = 0
        }
        publish()
    }

    /** Streams triangle positions from a binary STL into a vertex buffer, in chunks. */
    private fun uploadStl(file: File, expected: Long): Long {
        val triangles = minOf(expected, (file.length() - 84) / 50)
        if (triangles <= 0) return 0
        val bytes = triangles * 36
        if (bytes > Int.MAX_VALUE) throw IllegalStateException("Model too large to display")
        val ids = IntArray(1)
        glGenBuffers(1, ids, 0)
        meshVbo = ids[0]
        glBindBuffer(GL_ARRAY_BUFFER, meshVbo)
        glBufferData(GL_ARRAY_BUFFER, bytes.toInt(), null, GL_STATIC_DRAW)
        if (glGetError() == GL_OUT_OF_MEMORY) throw IllegalStateException("Not enough graphics memory for this model")

        val chunk = 32768
        val record = ByteArray(50 * chunk)
        val inBuf = ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN)
        val out = ByteBuffer.allocateDirect(36 * chunk).order(ByteOrder.nativeOrder())
        val outFloats: FloatBuffer = out.asFloatBuffer()
        var done = 0L
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
        glBindBuffer(GL_ARRAY_BUFFER, 0)
        return done
    }

    /** Bed plate, grid and build-volume outline for the current bed size. */
    private fun syncGeometry(w: Float, d: Float, h: Float) {
        if (geometryFor == Triple(w, d, h)) return
        geometryFor = Triple(w, d, h)
        for (vbo in intArrayOf(bedVbo, gridVbo, volumeVbo)) if (vbo != 0) glDeleteBuffers(1, intArrayOf(vbo), 0)
        bedVbo = buffer(floatArrayOf(0f, 0f, 0f, w, 0f, 0f, w, d, 0f, 0f, 0f, 0f, w, d, 0f, 0f, d, 0f))

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
        onStats(RenderStats(drawnTriangles, fps, lastUploadMillis, lastError))
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

        // Checked by tools/check_shaders.py with Khronos glslangValidator.
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
