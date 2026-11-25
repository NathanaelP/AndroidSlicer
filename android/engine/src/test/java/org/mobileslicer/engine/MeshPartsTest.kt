// SPDX-License-Identifier: AGPL-3.0-or-later
package org.mobileslicer.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MeshPartsTest {
    @get:Rule val tmp = TemporaryFolder()

    /** OBJ text for an axis-aligned box as 6 quads, with vertex numbers starting after [base]. */
    private fun box(base: Int, x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, quadsPerSide: Int = 1): String {
        val v = buildString {
            for (x in listOf(x0, x1)) for (y in listOf(y0, y1)) for (z in listOf(z0, z1)) append("v $x $y $z\n")
        }
        val q = listOf(intArrayOf(1, 2, 4, 3), intArrayOf(5, 7, 8, 6), intArrayOf(1, 5, 6, 2), intArrayOf(3, 4, 8, 7), intArrayOf(1, 3, 7, 5), intArrayOf(2, 6, 8, 4))
        val f = q.joinToString("") { s -> "f " + s.joinToString(" ") { (it + base).toString() } + "\n" }
        return v + f.repeat(quadsPerSide)
    }

    private fun trianglesOf(f: File): List<List<Float>> {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val n = b.getInt(80)
        return (0 until n).map { t -> (0 until 9).map { b.getFloat(84 + 50 * t + 12 + 4 * it) } }
    }

    @Test fun separatePiecesBecomeParts() {
        // A 20 mm cube on the bed and a small cube floating above it, as one file;
        // the big cube is written last but comes out first (largest first).
        val obj = box(0, 50f, 50f, 70f, 54f, 54f, 74f) + box(8, -10f, -10f, 0f, 10f, 10f, 20f, quadsPerSide = 2)
        val out = tmp.newFile("m.stl")
        val info = MeshImporter.import(ByteArrayInputStream(obj.toByteArray()), "two.obj", out)
        assertEquals(36, info.triangles)
        assertEquals(2, info.parts.size)
        val (big, small) = info.parts
        assertEquals(MeshPart(0, 24, Bounds(-10f, -10f, 0f, 10f, 10f, 20f), big.sampleStart, big.sampleCount), big)
        assertEquals(24, small.firstTriangle); assertEquals(12, small.triangles)
        assertEquals(Bounds(50f, 50f, 70f, 54f, 54f, 74f), small.bounds)
        // Each part has its own samples, and the triangles in each part's range are that part's.
        // Corners plus points across the faces of both cubes.
        assert(big.sampleCount > 8 && small.sampleCount > 8)
        val tris = trianglesOf(out)
        assert(tris.subList(24, 36).all { t -> t.all { it >= 50f } })
        assert(tris.subList(0, 24).all { t -> t.all { it <= 20f } })
        // No temporary file left behind.
        assertFalse(File(tmp.root, "m.stl.raw").exists())
        // Bounds per part after a turn use that part's samples.
        val turned = transformedBounds(info, small, Transform().thenRotate(Transform.rotationZ(45f)))
        assertEquals(4f * kotlin.math.sqrt(2f), turned.sizeX, 1e-3f)
    }

    @Test fun sameTrianglesAfterGrouping() {
        val obj = box(0, 0f, 0f, 0f, 1f, 1f, 1f) + box(8, 5f, 5f, 5f, 6f, 7f, 8f) + box(16, 2f, 0f, 0f, 3f, 1f, 1f)
        val out = tmp.newFile("m.stl")
        MeshImporter.import(ByteArrayInputStream(obj.toByteArray()), "three.obj", out)
        val raw = tmp.newFile("plain.stl")
        val plain = MeshImporter.import(ByteArrayInputStream(obj.toByteArray()), "three.obj", raw)
        assertEquals(3, plain.parts.size)
        assertEquals(trianglesOf(raw).sortedBy { it.toString() }, trianglesOf(out).sortedBy { it.toString() })
    }

    @Test fun triangleSoupIsOnePart() {
        // Every triangle has its own corners (nothing shared): more parts than MAX_PARTS -> one part.
        val n = MeshParts.MAX_PARTS + 5
        val stl = buildString {
            append("solid soup\n")
            for (i in 0 until n) {
                val x = (i % 200).toFloat(); val y = (i / 200).toFloat()
                append("facet normal 0 0 1\nouter loop\nvertex $x $y 0\nvertex ${x + 0.5f} $y 0\nvertex $x ${y + 0.5f} 0\nendloop\nendfacet\n")
            }
            append("endsolid soup\n")
        }
        val info = MeshImporter.import(ByteArrayInputStream(stl.toByteArray()), "soup.stl", tmp.newFile("s.stl"))
        assertEquals(1, info.parts.size)
        assertEquals(n, info.parts[0].triangles)
    }
}
