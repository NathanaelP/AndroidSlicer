// SPDX-License-Identifier: AGPL-3.0-or-later
package org.mobileslicer.app.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mobileslicer.app.viewport.Mat4
import org.mobileslicer.app.viewport.OrbitCamera
import org.mobileslicer.app.viewport.Vec3
import org.mobileslicer.engine.Bounds
import org.mobileslicer.engine.MeshImporter
import org.mobileslicer.engine.MeshInfo
import org.mobileslicer.engine.Transform
import java.io.ByteArrayInputStream
import java.io.File

class SceneTest {
    @get:Rule val tmp = TemporaryFolder()
    private val bed = BuildVolume(235f, 230f, 270f)

    private fun mesh(b: Bounds) = MeshInfo(File("unused.stl"), "STL", 12, b, 0)
    private fun obj(id: Long, b: Bounds, t: Transform = Transform(), x: Float = 117.5f, y: Float = 115f) =
        SceneObject(id, "o$id", mesh(b), t, x, y)

    /** A box exported off-centre and below zero, as sculpt exports often are: 10 x 40 x 10. */
    private val ybox = Bounds(-5f, 10f, -2f, 5f, 50f, 8f)

    @Test fun objectRestsOnTheBedAtItsPosition() {
        val o = obj(1, ybox, x = 100f, y = 60f)
        assertEquals(Bounds(95f, 40f, 0f, 105f, 80f, 10f), o.bounds)
        // The model matrix puts the mesh's corners at those bounds.
        val m = o.modelMatrix()
        val lo = Mat4.transform(m, Vec3(-5f, 10f, -2f)); val hi = Mat4.transform(m, Vec3(5f, 50f, 8f))
        assertEquals(Vec3(95f, 40f, 0f), lo); assertEquals(Vec3(105f, 80f, 10f), hi)
    }

    @Test fun uprightAndScaledMatchesTheEngineTransform() {
        val t = Transform(scale = 0.5f).thenRotate(Transform.rotationX(90f))
        val o = obj(1, ybox, t)
        // (x, y, z) -> (x, -z, y) * 0.5: 5 wide, 5 deep, 20 tall, centred on the bed, on Z = 0.
        assertEquals(Bounds(115f, 112.5f, 0f, 120f, 117.5f, 20f), o.bounds)
        val hi = Mat4.transform(o.modelMatrix(), Vec3(5f, 50f, -2f))
        assertEquals(120f, hi.x, 1e-4f); assertEquals(117.5f, hi.y, 1e-4f); assertEquals(20f, hi.z, 1e-4f)
    }

    @Test fun fitsAndOverlaps() {
        val cube = Bounds(-10f, -10f, 0f, 10f, 10f, 20f)
        assertTrue(obj(1, cube).fits(bed))
        assertFalse(obj(1, cube, x = 5f).fits(bed))                  // past the left edge
        assertFalse(obj(1, cube, Transform(scale = 14f)).fits(bed))    // 280 mm tall
        assertTrue(overlaps(obj(1, cube), obj(2, cube, x = 130f)))
        assertFalse(overlaps(obj(1, cube), obj(2, cube, x = 140f)))
    }

    @Test fun arrangeLaysObjectsOutWithoutOverlaps() {
        val cube = Bounds(-10f, -10f, 0f, 10f, 10f, 20f)
        val big = Bounds(-40f, -30f, 0f, 40f, 30f, 10f)
        val objs = listOf(obj(1, cube), obj(2, big), obj(3, cube), obj(4, ybox))
        val placed = arrange(objs, bed, spacing = 12f)!!
        assertEquals(objs.map { it.id }, placed.map { it.id }) // same order
        assertTrue(placed.all { it.fits(bed) })
        for (i in placed.indices) for (j in i + 1 until placed.size) assertFalse("$i/$j overlap", overlaps(placed[i], placed[j]))
        // Too many big objects for the bed: no arrangement.
        assertNull(arrange(List(12) { obj(it.toLong(), big) }, bed, 12f))
    }

    @Test fun newObjectsGoInTheMiddleOrEverythingIsRearranged() {
        val cube = Bounds(-10f, -10f, 0f, 10f, 10f, 20f)
        val first = placeNew(emptyList(), obj(1, cube, x = 0f, y = 0f), bed, 12f)
        assertEquals(117.5f, first.single().x); assertEquals(115f, first.single().y)
        val three = placeNew(placeNew(first, obj(2, cube), bed, 12f), obj(3, cube), bed, 12f)
        assertEquals(3, three.size)
        assertTrue(Scene(three).overlapping().isEmpty())
    }

    @Test fun historyUndoRedoAndMerging() {
        val h = History<Int>()
        var state = 0
        fun change(to: Int, key: String? = null) { h.record(state, key); state = to }
        change(1); change(2)
        change(3, "scale"); change(4, "scale"); change(5, "scale")  // typing: one step
        assertEquals(2, h.undo(state)!!.also { state = it })
        assertEquals(1, h.undo(state)!!.also { state = it })
        assertEquals(2, h.redo(state)!!.also { state = it })
        change(9)                                                   // a new change clears redo
        assertFalse(h.canRedo)
        assertEquals(2, h.undo(state))
    }

    @Test fun pickingObjectsAndFaces() {
        val cubeFile = tmp.newFile("cube.stl")
        val info = MeshImporter.import(ByteArrayInputStream(File("../../engine/test/cube20.stl").readBytes()), "cube.stl", cubeFile)
        val a = SceneObject(1, "a", info, Transform(), 80f, 115f)
        val b = SceneObject(2, "b", info, Transform(scale = 2f), 160f, 115f)
        // Straight down onto the middle of b: hits b's top face, normal +Z.
        val down = Ray(Vec3(160f, 115f, 200f), Vec3(0f, 0f, -1f))
        assertEquals(2L, pickObject(down, listOf(a, b))!!.id)
        val n = pickFaceNormal(down, b)!!
        assertEquals(1f, n.z, 1e-5f)
        // From the side at a: hits its -X face.
        val side = Ray(Vec3(0f, 115f, 10f), Vec3(1f, 0f, 0f))
        assertEquals(1L, pickObject(side, listOf(a, b))!!.id)
        assertEquals(-1f, pickFaceNormal(side, a)!!.x, 1e-5f)
        // Missing everything.
        assertNull(pickObject(Ray(Vec3(10f, 10f, 200f), Vec3(0f, 0f, -1f)), listOf(a, b)))
        // Lay flat on that side face: the face ends up pointing down, and on a rotated object too.
        val rotated = a.copy(transform = a.transform.thenRotate(Transform.rotationZ(30f)))
        val hit = pickFaceNormal(Ray(Vec3(80f, 115f, 200f), Vec3(0f, 0f, -1f)), rotated)!!  // top face, local +Z
        val rn = rotatedNormal(rotated.transform, hit)
        val flat = rotated.transform.thenRotate(Transform.pointingDown(rn[0], rn[1], rn[2]))
        val out = FloatArray(3).also { flat.apply(hit.x, hit.y, hit.z, it) }
        assertEquals(-1f, out[2], 1e-5f)
    }

    private fun rotatedNormal(t: Transform, n: Vec3): FloatArray =
        FloatArray(3).also { Transform(t.rotation).apply(n.x, n.y, n.z, it) }

    @Test fun screenRayGoesThroughTheTargetAtTheCentre() {
        val cam = OrbitCamera(Vec3(100f, 100f, 10f), 200f, yaw = -1f, pitch = 0.5f)
        val r = screenRay(cam, 1200f, 800f, 600f, 400f)
        val toTarget = (cam.target - r.origin).normalized()
        assertEquals(1f, r.dir.dot(toTarget), 1e-5f)
        // A ray from the top of the screen points higher than one from the bottom.
        assertTrue(screenRay(cam, 1200f, 800f, 600f, 0f).dir.z > screenRay(cam, 1200f, 800f, 600f, 800f).dir.z)
        // Where it meets the bed.
        val hit = rayPlaneZ(r, 10f)
        assertNotNull(hit); assertEquals(100f, hit!!.x, 1e-2f); assertEquals(100f, hit.y, 1e-2f)
    }
}
