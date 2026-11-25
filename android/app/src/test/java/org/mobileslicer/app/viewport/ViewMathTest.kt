// SPDX-License-Identifier: AGPL-3.0-or-later
package org.mobileslicer.app.viewport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mobileslicer.engine.Bounds
import org.mobileslicer.engine.Placement
import kotlin.math.PI

class ViewMathTest {
    private fun assertVec(expected: Vec3, actual: Vec3, eps: Float = 1e-3f) {
        assertEquals("x", expected.x, actual.x, eps)
        assertEquals("y", expected.y, actual.y, eps)
        assertEquals("z", expected.z, actual.z, eps)
    }

    /** A box exported off-centre and below zero, like many sculpt exports. */
    private val box = Bounds(-5f, 10f, -2f, 5f, 50f, 8f) // 10 x 40 x 10

    @Test fun modelIsCentredOnTheBedAndRestsOnIt() {
        val m = modelMatrix(box, Placement(1f, false), 235f, 230f)
        assertVec(Vec3(112.5f, 95f, 0f), Mat4.transform(m, Vec3(-5f, -10f + 20f, -2f)))  // min corner
        assertVec(Vec3(122.5f, 135f, 10f), Mat4.transform(m, Vec3(5f, 50f, 8f)))        // max corner
    }

    @Test fun uprightTurnsYUpIntoZUpLikeTheEngine() {
        val m = modelMatrix(box, Placement(0.5f, true), 235f, 230f)
        // Upright maps (x, y, z) -> (x, -z, y) * scale: height comes from Y (40 mm * 0.5 = 20 mm).
        val lo = Mat4.transform(m, Vec3(-5f, 10f, 8f))
        val hi = Mat4.transform(m, Vec3(5f, 50f, -2f))
        assertVec(Vec3(115f, 112.5f, 0f), lo)
        assertVec(Vec3(120f, 117.5f, 20f), hi)
        // The engine's matrix string for the same placement has the same rows.
        assertEquals("[[0.5,0,0],[0,0,-0.5],[0,0.5,0]]", Placement(0.5f, true).matrix())
    }

    @Test fun cameraLooksAtItsTarget() {
        val cam = OrbitCamera(Vec3(100f, 100f, 0f), 200f, yaw = 0f, pitch = 0f)
        // The target is straight ahead: in view space it sits on the -Z axis at the camera distance.
        assertVec(Vec3(0f, 0f, -200f), Mat4.transform(cam.view(), cam.target))
        val clip = Mat4.mul(cam.projection(1.5f), cam.view())
        val p = Mat4.transform(clip, cam.target)
        assertEquals(0f, p.x, 1e-3f); assertEquals(0f, p.y, 1e-3f)
    }

    @Test fun orbitPanZoom() {
        val cam = OrbitCamera(Vec3(0f, 0f, 0f), 100f, yaw = 0f, pitch = 0.2f)
        // Dragging the full view height turns half a circle; pitch is clamped.
        assertEquals(-PI.toFloat(), cam.orbit(1000f, 0f, 1000f).yaw, 1e-4f)
        assertEquals(OrbitCamera.MAX_PITCH, cam.orbit(0f, 5000f, 1000f).pitch, 1e-6f)
        // Pinch out to zoom in; clamped to the limits.
        assertEquals(50f, cam.zoom(2f).distance, 1e-4f)
        assertEquals(OrbitCamera.MIN_DISTANCE, cam.zoom(1e6f).distance, 1e-4f)
        assertEquals(100f, cam.zoom(Float.NaN).distance, 1e-4f)
        // Panning moves the target sideways and keeps the distance.
        val panned = cam.pan(100f, 0f, 1000f)
        assertTrue(panned.target.length() > 0f)
        assertEquals(100f, (panned.eye() - panned.target).length(), 1e-3f)
        assertEquals(0f, (panned.target - cam.target).dot(cam.target - cam.eye()), 1e-3f) // perpendicular to the view
    }

    @Test fun cameraStaysAboveTheBed() {
        val cam = OrbitCamera(Vec3(100f, 100f, 10f), 60f, yaw = 0f, pitch = 0.3f)
        // Dragging down as far as possible stops with the eye just above the bed.
        val low = cam.orbit(0f, -100000f, 1000f)
        assertEquals(OrbitCamera.MIN_EYE_HEIGHT, low.eye().z, 1e-3f)
        // Zooming out from a low view doesn't take the eye under the bed either.
        assertTrue(low.zoom(0.25f).eye().z >= OrbitCamera.MIN_EYE_HEIGHT - 1e-3f)
        // Panning the target down to the bed keeps the eye above it.
        assertTrue(low.pan(0f, -100000f, 1000f).eye().z >= OrbitCamera.MIN_EYE_HEIGHT - 1e-3f)
        // With a tall target the camera may look up from slightly below level.
        val tall = OrbitCamera(Vec3(100f, 100f, 100f), 60f, yaw = 0f, pitch = 0.3f).orbit(0f, -100000f, 1000f)
        assertEquals(OrbitCamera.MIN_PITCH, tall.pitch, 1e-6f)
    }

    @Test fun overviewSeesTheWholeVolume() {
        val cam = OrbitCamera.overview(235f, 230f, 270f)
        val clip = Mat4.mul(cam.projection(1f), cam.view())
        for (x in listOf(0f, 235f)) for (y in listOf(0f, 230f)) for (z in listOf(0f, 270f)) {
            val p = Mat4.transform(clip, Vec3(x, y, z))
            // w for a perspective matrix is -z_view; recompute it to normalise.
            val w = -Mat4.transform(cam.view(), Vec3(x, y, z)).z
            assertTrue("corner $x,$y,$z in view", kotlin.math.abs(p.x / w) <= 1.05f && kotlin.math.abs(p.y / w) <= 1.05f)
        }
    }
}
