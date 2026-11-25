// SPDX-License-Identifier: AGPL-3.0-or-later
package org.mobileslicer.app.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mobileslicer.engine.MeshImporter
import org.mobileslicer.engine.MeshInfo
import org.mobileslicer.engine.Transform
import java.io.ByteArrayInputStream

class FloatingTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun box(base: Int, x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float): String {
        val v = buildString { for (x in listOf(x0, x1)) for (y in listOf(y0, y1)) for (z in listOf(z0, z1)) append("v $x $y $z\n") }
        val q = listOf(intArrayOf(1, 2, 4, 3), intArrayOf(5, 7, 8, 6), intArrayOf(1, 5, 6, 2), intArrayOf(3, 4, 8, 7), intArrayOf(1, 3, 7, 5), intArrayOf(2, 6, 8, 4))
        return v + q.joinToString("") { s -> "f " + s.joinToString(" ") { (it + base).toString() } + "\n" }
    }

    /** A 20 mm cube on the bed plus a 4 mm cube at the given position, as one mesh. */
    private fun withSmallCube(x: Float, y: Float, z: Float): MeshInfo {
        val obj = box(0, -10f, -10f, 0f, 10f, 10f, 20f) + box(8, x, y, z, x + 4f, y + 4f, z + 4f)
        return MeshImporter.import(ByteArrayInputStream(obj.toByteArray()), "m.obj", tmp.newFile())
    }

    private fun obj(m: MeshInfo, t: Transform = Transform()) = SceneObject(1, "m", m, t, 117.5f, 115f)

    @Test fun loosePieceAboveTheModelFloats() {
        val f = obj(withSmallCube(-2f, -2f, 50f)).floatingParts()
        assertEquals(1, f.size)
        assertEquals(1, f[0].part)
        assertEquals(50f, f[0].height, 1e-4f)
        assertEquals(12, f[0].triangles)
    }

    @Test fun piecesRestingOnOrInsideTheModelDoNot() {
        for ((name, at) in listOf(
            "sitting on top" to floatArrayOf(-2f, -2f, 20f),
            "half buried in the top" to floatArrayOf(-2f, -2f, 18f),
            "sticking out of a side" to floatArrayOf(8f, -2f, 8f),
            "on the bed beside it" to floatArrayOf(30f, 0f, 0f),
        )) {
            val f = obj(withSmallCube(at[0], at[1], at[2])).floatingParts()
            assertTrue("$name: $f", f.isEmpty())
        }
    }

    @Test fun turningTheModelChangesWhatFloats() {
        // On the bed beside the cube: fine. Upside down, the small cube ends up at the top of the
        // big one's height (16..20 mm), off to the side with nothing under it.
        val m = withSmallCube(30f, 0f, 0f)
        assertTrue(obj(m).floatingParts().isEmpty())
        val flipped = obj(m, Transform().thenRotate(Transform.rotationX(180f))).floatingParts()
        assertEquals(1, flipped.size)
        assertEquals(16f, flipped[0].height, 1e-4f)
        // Scaling scales the gap.
        assertEquals(32f, obj(m, Transform(scale = 2f).thenRotate(Transform.rotationX(180f))).floatingParts()[0].height, 1e-4f)
    }
}
