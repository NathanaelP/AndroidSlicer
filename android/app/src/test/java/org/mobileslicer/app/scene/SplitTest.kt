// SPDX-License-Identifier: AGPL-3.0-or-later
package org.mobileslicer.app.scene

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mobileslicer.engine.MeshImporter
import org.mobileslicer.engine.MeshParts
import org.mobileslicer.engine.Transform
import java.io.ByteArrayInputStream

class SplitTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun box(base: Int, x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float): String {
        val v = buildString { for (x in listOf(x0, x1)) for (y in listOf(y0, y1)) for (z in listOf(z0, z1)) append("v $x $y $z\n") }
        val q = listOf(intArrayOf(1, 2, 4, 3), intArrayOf(5, 7, 8, 6), intArrayOf(1, 5, 6, 2), intArrayOf(3, 4, 8, 7), intArrayOf(1, 3, 7, 5), intArrayOf(2, 6, 8, 4))
        return v + q.joinToString("") { s -> "f " + s.joinToString(" ") { (it + base).toString() } + "\n" }
    }

    @Test fun floatingPieceBecomesItsOwnObjectAndDropsToTheBed() {
        // A 20 mm cube with a hat on it, and a loose cube floating 40 mm up to the side.
        val obj = box(0, -10f, -10f, 0f, 10f, 10f, 20f) + box(8, -5f, -5f, 20f, 5f, 5f, 24f) + box(16, 20f, 0f, 60f, 26f, 6f, 66f)
        val mesh = MeshImporter.import(ByteArrayInputStream(obj.toByteArray()), "fig.obj", tmp.newFile("m.stl"))
        val o = SceneObject(1, "fig.obj", mesh, Transform(scale = 2f), 100f, 100f)
        assertEquals(1, o.floating.size)
        val groups = o.splitGroups()
        assertEquals(listOf(2, 1), groups.map { it.size })
        val pieces = o.splitInto(groups.mapIndexed { i, g -> MeshParts.extract(mesh, g, tmp.newFile("p$i.stl")) }, listOf(7, 8))
        assertEquals(listOf("fig (1).obj", "fig (2).obj"), pieces.map { it.name })
        assertEquals(listOf(7L, 8L), pieces.map { it.id })
        val (body, loose) = pieces
        // Same place over the bed as before, nothing floating, and the loose cube now on the bed.
        val before = o.bounds
        assertEquals(before.minX, body.bounds.minX, 1e-3f)
        assertEquals(before.maxX, loose.bounds.maxX, 1e-3f)
        assertEquals(before.minY, body.bounds.minY, 1e-3f)
        assertEquals(0f, loose.bounds.minZ, 1e-6f)
        assertEquals(12f, loose.bounds.sizeZ, 1e-3f)
        assertEquals(48f, body.bounds.sizeZ, 1e-3f)
        assert(pieces.all { it.floating.isEmpty() })
        assertEquals(2f, loose.transform.scale)
    }

    @Test fun touchingPartsStayOneObject() {
        val obj = box(0, -10f, -10f, 0f, 10f, 10f, 20f) + box(8, -5f, -5f, 20f, 5f, 5f, 24f)
        val mesh = MeshImporter.import(ByteArrayInputStream(obj.toByteArray()), "hat.obj", tmp.newFile("m.stl"))
        assertEquals(2, mesh.parts.size)
        assertEquals(1, SceneObject(1, "hat.obj", mesh, Transform(), 100f, 100f).splitGroups().size)
    }
}
