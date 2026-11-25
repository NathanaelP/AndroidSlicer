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
import kotlin.math.sqrt

class TransformTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun apply(t: Transform, x: Float, y: Float, z: Float) = FloatArray(3).also { t.apply(x, y, z, it) }

    private fun assertVec(e: FloatArray, a: FloatArray, eps: Float = 1e-5f) {
        for (i in 0 until 3) assertEquals("component $i", e[i], a[i], eps)
    }

    @Test fun matrixStringMatchesTheEngineFormat() {
        assertEquals("[[1.0,0,0],[0,1.0,0],[0,0,1.0]]", Transform().matrix())
        // Standing a Y-up model upright: +90 degrees around X maps (x, y, z) to (x, -z, y).
        val upright = Transform(scale = 0.5f).thenRotate(Transform.rotationX(90f))
        assertEquals("[[0.5,0,0],[0,0,-0.5],[0,0.5,0]]", upright.matrix())
        assertVec(floatArrayOf(1f, -3f, 2f), apply(Transform().thenRotate(Transform.rotationX(90f)), 1f, 2f, 3f))
        assertTrue(upright.isAxisAligned())
    }

    @Test fun rotationsCompose() {
        // Four quarter turns are the identity, exactly.
        var t = Transform()
        repeat(4) { t = t.thenRotate(Transform.rotationZ(90f)) }
        assertEquals(Transform.IDENTITY, t.rotation)
        // 45 degrees around Z isn't axis-aligned.
        val r = Transform().thenRotate(Transform.rotationZ(45f))
        assertFalse(r.isAxisAligned())
        val h = sqrt(0.5f)
        assertVec(floatArrayOf(h, h, 0f), apply(r, 1f, 0f, 0f))
        // Later rotations apply after earlier ones.
        val xz = Transform().thenRotate(Transform.rotationX(90f)).thenRotate(Transform.rotationZ(90f))
        assertVec(floatArrayOf(0f, 0f, 1f), apply(xz, 0f, 1f, 0f)) // y -> z (X turn) -> z (Z turn)
        assertVec(floatArrayOf(0f, 1f, 0f), apply(xz, 1f, 0f, 0f)) // x -> x -> y
    }

    @Test fun pointingDownLaysAFaceOnTheBed() {
        val normals = listOf(
            floatArrayOf(0f, 0f, -1f), floatArrayOf(0f, 0f, 1f), floatArrayOf(1f, 0f, 0f),
            floatArrayOf(0f, -1f, 0f), floatArrayOf(1f, 2f, 3f), floatArrayOf(-0.3f, 0.1f, -0.9f),
        )
        for (n in normals) {
            val t = Transform(Transform.pointingDown(n[0], n[1], n[2]))
            val len = sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2])
            assertVec(floatArrayOf(0f, 0f, -1f), apply(t, n[0] / len, n[1] / len, n[2] / len))
            // Still a rotation: lengths are kept.
            val v = apply(t, 3f, 4f, 0f)
            assertEquals(5f, sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]), 1e-4f)
        }
    }

    @Test fun boundsAfterRotation() {
        val cube = File("../../engine/test/cube20.stl").readBytes()
        val info = MeshImporter.import(ByteArrayInputStream(cube), "cube.stl", tmp.newFile("c.stl"))
        assert(info.samples.size > 8 * 3) // the 8 corners plus points across the faces
        // Axis-aligned: exact, from the import bounds.
        val up = transformedBounds(info, Transform(scale = 2f).thenRotate(Transform.rotationX(90f)))
        assertEquals(Bounds(-20f, -40f, -20f, 20f, 0f, 20f), up)
        // 45 degrees around Z: the footprint grows to the diagonal, from the samples.
        val r = transformedBounds(info, Transform().thenRotate(Transform.rotationZ(45f)))
        assertEquals(20f * sqrt(2f), r.sizeX, 1e-3f)
        assertEquals(20f * sqrt(2f), r.sizeY, 1e-3f)
        assertEquals(20f, r.sizeZ, 1e-4f)
    }

    @Test fun commandForSeveralObjects() {
        val profile = Profile("p", "P", "Q", "M", "PLA", 1.24, linkedMapOf("machine_width" to "235", "machine_depth" to "230"))
        val a = PlacedObject(File("/m/a.stl"), Transform(), 67.5f, 115f)
        val b = PlacedObject(File("/m/b.stl"), Transform(scale = 2f), 117.5f, 155f)
        val cmd = EngineRunner.buildCommand(File("/e/eng"), File("/d"), profile, listOf(a, b), File("/o/x.gcode"))
        val tail = cmd.subList(cmd.indexOf("-e0") + 5, cmd.size) // after the extruder's profile settings
        assertEquals(
            listOf(
                "-e0", "-s", "mesh_rotation_matrix=[[1.0,0,0],[0,1.0,0],[0,0,1.0]]", "-l", "/m/a.stl",
                "-s", "center_object=True", "-s", "mesh_position_x=-50.0", "-s", "mesh_position_y=0.0", "-s", "mesh_position_z=0",
                "-e0", "-s", "mesh_rotation_matrix=[[2.0,0,0],[0,2.0,0],[0,0,2.0]]", "-l", "/m/b.stl",
                "-s", "center_object=True", "-s", "mesh_position_x=0.0", "-s", "mesh_position_y=40.0", "-s", "mesh_position_z=0",
                "-o", "/o/x.gcode",
            ),
            tail,
        )
        assertEquals(listOf("/e/eng", "slice"), cmd.take(2))
    }
}
