// SPDX-License-Identifier: AGPL-3.0-or-later
package org.mobileslicer.app.viewport

import org.junit.Test
import org.mobileslicer.engine.Bounds
import org.mobileslicer.engine.Placement
import java.io.File

/**
 * Writes the camera and model matrices the app computes for a few scenes, for
 * tools/render_preview.py, which draws them with the real shaders in WebGL2 to
 * check the rendering on a desktop. Output: app/build/viewport-scenes.json
 */
class ViewportScenesTest {
    private fun arr(m: FloatArray) = m.joinToString(",", "[", "]")

    @Test fun writeScenes() {
        val w = 235f; val d = 230f; val h = 270f
        val aspect = 1.5f
        // The 20 mm test cube as imported: centred on its own origin, z from 0 to 20.
        val cube = Bounds(-10f, -10f, 0f, 10f, 10f, 20f)
        // A Y-up export lying on its back: 10 x 40 x 10, off-centre.
        val ybox = Bounds(-5f, 10f, -2f, 5f, 50f, 8f)
        val overview = OrbitCamera.overview(w, d, h)

        data class Scene(val name: String, val camera: OrbitCamera, val mesh: String?, val bounds: Bounds?, val placement: Placement, val color: String)
        val scenes = listOf(
            Scene("empty-bed", overview, null, null, Placement(), "amber"),
            Scene("cube-overview", overview, "cube", cube, Placement(), "amber"),
            Scene("cube-close", OrbitCamera.framing(w, d, Placement().placedSize(cube), yaw = -1.1f, pitch = 0.45f), "cube", cube, Placement(), "amber"),
            Scene("ybox-as-exported", OrbitCamera.framing(w, d, Placement(2f).placedSize(ybox), yaw = -1.1f, pitch = 0.45f), "ybox", ybox, Placement(2f), "amber"),
            Scene("ybox-upright", OrbitCamera.framing(w, d, Placement(2f, true).placedSize(ybox), yaw = -1.1f, pitch = 0.45f), "ybox", ybox, Placement(2f, true), "amber"),
            Scene("too-big", overview, "cube", cube, Placement(13f), "red"),
            // Dragged down as far as it goes: the camera stops just above the bed.
            Scene("cube-lowest-view", OrbitCamera.framing(w, d, Placement().placedSize(cube), yaw = -1.1f, pitch = 0.45f).orbit(0f, -100000f, 800f), "cube", cube, Placement(), "amber"),
            Scene("sphere-close", OrbitCamera(Vec3(w / 2, d / 2, 40f), 160f, yaw = -1.2f, pitch = 0.35f), "sphere", Bounds(-40f, -40f, -40f, 40f, 40f, 40f), Placement(), "amber"),
        )
        val json = scenes.joinToString(",\n", "[\n", "\n]") { s ->
            val model = s.bounds?.let { modelMatrix(it, s.placement, w, d) }
            val viewProj = Mat4.mul(s.camera.projection(aspect), s.camera.view())
            """  {"name":"${s.name}","mesh":${s.mesh?.let { "\"$it\"" } ?: "null"},"color":"${s.color}",""" +
                """"bed":[$w,$d,$h],"viewProj":${arr(viewProj)},"view":${arr(s.camera.view())},""" +
                """"model":${model?.let { arr(it) } ?: "null"}}"""
        }
        File("build").mkdirs()
        File("build/viewport-scenes.json").writeText(json)
    }
}
