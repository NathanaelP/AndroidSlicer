// SPDX-License-Identifier: AGPL-3.0-or-later
package org.mobileslicer.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Needs the host build of libmeshsimplify (engine/build-simplifier.sh host); skipped without it. */
class SimplifyTest {
    @get:Rule val tmp = TemporaryFolder()

    @Before fun needsNative() = assumeTrue("no host build of libmeshsimplify", NativeSimplifier.available)

    /** A closed UV sphere of radius [r] as a binary STL, [lat] x [lon] quads. */
    private fun sphere(f: File, r: Float, lat: Int, lon: Int): Long {
        fun p(i: Int, j: Int): FloatArray {
            // The seam (j = lon) and the poles use exactly the same corners, so the sphere is closed.
            val jj = j % lon
            val t = Math.PI * i / lat; val ph = 2 * Math.PI * jj / lon
            if (i == 0) return floatArrayOf(0f, 0f, r + r)
            if (i == lat) return floatArrayOf(0f, 0f, 0f)
            return floatArrayOf((r * sin(t) * cos(ph)).toFloat(), (r * sin(t) * sin(ph)).toFloat(), (r + r * cos(t)).toFloat())
        }
        var count = 0L
        val rec = ByteBuffer.allocate(50).order(ByteOrder.LITTLE_ENDIAN)
        java.io.RandomAccessFile(f, "rw").use { raf ->
            val out = java.io.BufferedOutputStream(java.io.FileOutputStream(raf.fd), 1 shl 16)
            out.write(ByteArray(84))
            fun tri(a: FloatArray, b: FloatArray, c: FloatArray) {
                rec.clear(); repeat(3) { rec.putFloat(0f) }
                for (v in arrayOf(a, b, c)) v.forEach { rec.putFloat(it) }
                rec.putShort(0); out.write(rec.array()); count++
            }
            for (i in 0 until lat) for (j in 0 until lon) {
                val a = p(i, j); val b = p(i + 1, j); val c = p(i + 1, j + 1); val d = p(i, j + 1)
                if (i != 0) tri(a, b, d)
                if (i != lat - 1) tri(b, c, d)
            }
            out.flush()
            raf.seek(80)
            raf.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(count.toInt()).array())
        }
        return count
    }

    @Test fun simplifiedSphereStaysWithinTheError() {
        val src = tmp.newFile("s.stl")
        val n = sphere(src, 20f, 200, 400)
        val m = MeshImporter.import(src.inputStream(), "s.stl", tmp.newFile("m.stl"))
        assertEquals(n, m.triangles)
        assertTrue(m.health.clean)
        val s = MeshSimplifier.simplify(m, maxError = 0.02f, out = tmp.newFile("o.stl"))
        // Far fewer triangles, still closed, and every corner still on the sphere within the error.
        assertTrue("${s.mesh.triangles}", s.mesh.triangles < n / 4)
        assertTrue("${s.mesh.health}", s.mesh.health.clean)
        assertTrue("error ${s.error} triangles ${s.mesh.triangles}", s.error <= 0.02f)
        assertEquals(n, s.mesh.simplifiedFrom)
        val b = ByteBuffer.wrap(s.mesh.stl.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        for (t in 0 until s.mesh.triangles.toInt()) for (k in 0 until 3) {
            val o = 84 + 50 * t + 12 + 12 * k
            val x = b.getFloat(o); val y = b.getFloat(o + 4); val z = b.getFloat(o + 8) - 20f
            assertEquals(20f, sqrt(x * x + y * y + z * z), 1e-3f)  // corners are kept vertices
        }
        assertEquals(m.bounds.sizeZ, s.mesh.bounds.sizeZ, 1e-3f)
    }

    @Test fun denseMeshIsSimplifiedOnImport() {
        val src = tmp.newFile("d.stl")
        val n = sphere(src, 30f, 1800, 1800)  // about 6.5 million triangles
        assertTrue(n > MeshImporter.AUTO_SIMPLIFY_ABOVE)
        val m = MeshImporter.import(src.inputStream(), "d.stl", tmp.newFile("m.stl"), onSimplifying = { assertEquals(n, it) })
        assertEquals(n, m.simplifiedFrom)
        assertTrue("${m.triangles}", m.triangles <= MeshImporter.AUTO_SIMPLIFY_TARGET)
        assertTrue(m.simplifyError < 60f * sqrt(3f) * MeshImporter.AUTO_SIMPLIFY_MAX_ERROR)
        assertTrue(m.health.clean)
        assertEquals(60f, m.bounds.sizeZ, 1e-3f)
    }

    @Test fun holeRimsAreKept() {
        // An open box (no bottom), finely divided: its rim must stay exactly where it was.
        val obj = buildString {
            val n = 40
            var id = 0
            val ids = HashMap<Triple<Float, Float, Float>, Int>()
            val faces = StringBuilder()
            fun v(x: Float, y: Float, z: Float) = ids.getOrPut(Triple(x, y, z)) { append("v $x $y $z\n"); ++id }
            fun face(o: FloatArray, u: FloatArray, w: FloatArray) {
                fun p(i: Int, j: Int) = v(o[0] + u[0] * i / n + w[0] * j / n, o[1] + u[1] * i / n + w[1] * j / n, o[2] + u[2] * i / n + w[2] * j / n)
                for (i in 0 until n) for (j in 0 until n) faces.append("f ${p(i, j)} ${p(i + 1, j)} ${p(i + 1, j + 1)} ${p(i, j + 1)}\n")
            }
            face(floatArrayOf(0f, 0f, 10f), floatArrayOf(10f, 0f, 0f), floatArrayOf(0f, 10f, 0f))
            face(floatArrayOf(0f, 0f, 0f), floatArrayOf(10f, 0f, 0f), floatArrayOf(0f, 0f, 10f))
            face(floatArrayOf(0f, 10f, 0f), floatArrayOf(0f, 0f, 10f), floatArrayOf(10f, 0f, 0f))
            face(floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 10f), floatArrayOf(0f, 10f, 0f))
            face(floatArrayOf(10f, 0f, 0f), floatArrayOf(0f, 10f, 0f), floatArrayOf(0f, 0f, 10f))
            append(faces)
        }
        val m = MeshImporter.import(obj.byteInputStream(), "open.obj", tmp.newFile("m.stl"))
        assertEquals(1, m.health.holes.size)
        val s = MeshSimplifier.simplify(m, 0.01f, tmp.newFile("o.stl")).mesh
        assertTrue(s.triangles < m.triangles / 10)
        assertEquals(m.health.openEdges, s.health.openEdges)
        assertEquals(1, s.health.holes.size)
        // Still fillable to a clean mesh.
        assertTrue(MeshRepair.fillHoles(s, tmp.newFile("f.stl")).health.clean)
    }
}
