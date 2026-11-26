// SPDX-License-Identifier: AGPL-3.0-or-later
package org.mobileslicer.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MeshHealthTest {
    @get:Rule val tmp = TemporaryFolder()

    /**
     * An axis-aligned box as OBJ, outward-facing, each face split into [n] x [n]
     * quads so the open base is a rim of 4n edges. [skip] leaves faces out (by
     * name), [flip] writes faces the wrong way round.
     */
    private fun box(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, n: Int = 1, skip: Set<String> = emptySet(), flip: Set<String> = emptySet()): String {
        val sb = StringBuilder()
        val ids = HashMap<Triple<Float, Float, Float>, Int>()
        fun v(x: Float, y: Float, z: Float) = ids.getOrPut(Triple(x, y, z)) { sb.append("v $x $y $z\n"); ids.size + 1 }
        val faces = StringBuilder()
        // Each face: origin, two edge vectors (u x v points outwards).
        val fs = mapOf(
            "bottom" to listOf(floatArrayOf(x0, y0, z0), floatArrayOf(0f, y1 - y0, 0f), floatArrayOf(x1 - x0, 0f, 0f)),
            "top" to listOf(floatArrayOf(x0, y0, z1), floatArrayOf(x1 - x0, 0f, 0f), floatArrayOf(0f, y1 - y0, 0f)),
            "front" to listOf(floatArrayOf(x0, y0, z0), floatArrayOf(x1 - x0, 0f, 0f), floatArrayOf(0f, 0f, z1 - z0)),
            "back" to listOf(floatArrayOf(x0, y1, z0), floatArrayOf(0f, 0f, z1 - z0), floatArrayOf(x1 - x0, 0f, 0f)),
            "left" to listOf(floatArrayOf(x0, y0, z0), floatArrayOf(0f, 0f, z1 - z0), floatArrayOf(0f, y1 - y0, 0f)),
            "right" to listOf(floatArrayOf(x1, y0, z0), floatArrayOf(0f, y1 - y0, 0f), floatArrayOf(0f, 0f, z1 - z0)),
        )
        for ((name, f) in fs) {
            if (name in skip) continue
            val (o, u, w) = f
            fun p(i: Int, j: Int) = v(o[0] + u[0] * i / n + w[0] * j / n, o[1] + u[1] * i / n + w[1] * j / n, o[2] + u[2] * i / n + w[2] * j / n)
            for (i in 0 until n) for (j in 0 until n) {
                val q = listOf(p(i, j), p(i + 1, j), p(i + 1, j + 1), p(i, j + 1)).let { if (name in flip) it.reversed() else it }
                faces.append("f ${q.joinToString(" ")}\n")
            }
        }
        return sb.toString() + faces
    }

    private fun import(obj: String, name: String = "m") =
        MeshImporter.import(ByteArrayInputStream(obj.toByteArray()), "$name.obj", tmp.newFile("$name.stl"))

    @Test fun closedBoxIsClean() {
        val h = import(box(0f, 0f, 0f, 10f, 10f, 10f, n = 3)).health
        assertTrue(h.clean)
        assertEquals(PartHealth(), h)
    }

    @Test fun openBaseIsOneHole() {
        val m = import(box(0f, 0f, 0f, 10f, 20f, 10f, n = 4, skip = setOf("bottom")))
        val h = m.health
        assertEquals(16, h.openEdges)
        assertEquals(1, h.holes.size)
        assertEquals(16 * 3, h.holes[0].size)
        assertEquals(0, h.untracedOpenEdges)
        assertTrue(h.holes[0].withIndex().all { (i, z) -> i % 3 != 2 || z == 0f })  // the rim is the base
        assertFalse(h.closed)
    }

    @Test fun flippedFaceAndDuplicateFace() {
        val flipped = import(box(0f, 0f, 0f, 10f, 10f, 10f, flip = setOf("top")), "f").health
        // The top's 4 rim edges run the same way as the sides'; its own diagonal is fine.
        assertEquals(4, flipped.flippedEdges)
        assertEquals(0, flipped.openEdges)
        val extra = "v 0 0 10\nv 10 0 10\nv 10 10 10\nf 1 2 3\n"  // a stray triangle on the top face
        val twice = import(box(0f, 0f, 0f, 10f, 10f, 10f) + extra.replace("f 1 2 3", "f 9 10 11"), "d").health
        assertTrue(twice.nonManifoldEdges > 0)
    }

    @Test fun degenerateTrianglesAreCounted() {
        val obj = box(0f, 0f, 0f, 10f, 10f, 10f) + "f 1 1 2\n"
        val h = import(obj).health
        assertEquals(1, h.degenerateTriangles)
        assertEquals(0, h.openEdges)
    }

    @Test fun fillHolesClosesOpenBaseAndSide() {
        val m = import(box(0f, 0f, 0f, 10f, 20f, 10f, n = 3, skip = setOf("bottom", "left")))
        // Bottom and left share an edge, so the two openings are one rim.
        assertEquals(1, m.health.holes.size)
        val fixed = MeshRepair.fillHoles(m, tmp.newFile("fixed.stl"))
        assertTrue(fixed.health.toString(), fixed.health.clean)
        assertEquals(m.triangles + m.health.openEdges, fixed.triangles)
        assertEquals(m.bounds, fixed.bounds)
        // Clean means no flipped edges either: the fill runs the same way round as the box.
        val b = ByteBuffer.wrap(fixed.stl.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(fixed.triangles.toInt(), b.getInt(80))
        assertFalse(File(tmp.root, "fixed.stl.raw").exists())
    }

    @Test fun twoHolesInTwoParts() {
        val obj = box(0f, 0f, 0f, 10f, 10f, 10f, skip = setOf("top")) + box(20f, 0f, 0f, 30f, 10f, 10f, skip = setOf("bottom")).let { o ->
            // Renumber the second box's vertices after the first's 8.
            o.lines().joinToString("\n") { l -> if (l.startsWith("f ")) "f " + l.drop(2).split(" ").joinToString(" ") { (it.toInt() + 8).toString() } else l }
        }
        val m = import(obj)
        assertEquals(2, m.parts.size)
        assertTrue(m.parts.all { it.health.holes.size == 1 && it.health.openEdges == 4 })
        val fixed = MeshRepair.fillHoles(m, tmp.newFile("fixed.stl"))
        assertTrue(fixed.health.clean)
        assertEquals(2, fixed.parts.size)
    }
}
