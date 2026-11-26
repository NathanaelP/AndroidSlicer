// SPDX-License-Identifier: AGPL-3.0-or-later
package org.mobileslicer.app.viewport

import org.junit.Test
import org.mobileslicer.app.scene.BuildVolume
import org.mobileslicer.app.scene.SceneObject
import org.mobileslicer.app.scene.arrange
import org.mobileslicer.engine.Bounds
import org.mobileslicer.engine.MeshImporter
import org.mobileslicer.engine.MeshInfo
import org.mobileslicer.engine.Transform
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Writes the camera and model matrices the app computes for a few scenes, for
 * tools/render_preview.py, which draws them with the real shaders in WebGL2 to
 * check the rendering on a desktop. Output: app/build/viewport-scenes.json
 */
class ViewportScenesTest {
    private fun arr(m: FloatArray) = m.joinToString(",", "[", "]")

    /** OBJ text for a box as 6 quads, vertex numbers starting after [base]. */
    private fun box(base: Int, x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float): String {
        val v = buildString { for (x in listOf(x0, x1)) for (y in listOf(y0, y1)) for (z in listOf(z0, z1)) append("v $x $y $z\n") }
        val q = listOf(intArrayOf(1, 2, 4, 3), intArrayOf(5, 7, 8, 6), intArrayOf(1, 5, 6, 2), intArrayOf(3, 4, 8, 7), intArrayOf(1, 3, 7, 5), intArrayOf(2, 6, 8, 4))
        return v + q.joinToString("") { s -> "f " + s.joinToString(" ") { (it + base).toString() } + "\n" }
    }

    @Test fun writeScenes() {
        val v = BuildVolume(235f, 230f, 270f)
        val aspect = 1.5f
        // Real meshes, imported the way the app does, so the harness draws the same
        // triangles in the same (grouped by part) order: the 20 mm test cube, a Y-up
        // export lying on its back (10 x 40 x 10, off-centre), and a cube with a small
        // loose cube floating 40 mm above it.
        val dir = File("build/preview-meshes").apply { mkdirs() }
        fun import(name: String, text: String) =
            MeshImporter.import(ByteArrayInputStream(text.toByteArray()), "$name.obj", File(dir, "$name.stl"))
        val cube = MeshImporter.import(File("../../engine/test/cube20.stl").inputStream(), "cube.stl", File(dir, "cube.stl"))
        val ybox = import("ybox", box(0, -5f, 10f, -2f, 5f, 50f, 8f))
        val loose = import("loose", box(0, -10f, -10f, 0f, 10f, 10f, 20f) + box(8, 4f, 4f, 60f, 10f, 10f, 66f))
        fun obj(id: Long, m: MeshInfo, t: Transform = Transform(), x: Float = 117.5f, y: Float = 115f) = SceneObject(id, m.stl.name, m, t, x, y)

        val overview = OrbitCamera.overview(v.width, v.depth, v.height)
        val three = arrange(
            listOf(
                obj(1, cube),
                obj(2, cube, Transform(scale = 1.5f).thenRotate(Transform.rotationZ(45f))),
                obj(3, ybox, Transform(scale = 2f).thenRotate(Transform.rotationX(90f))),
            ),
            v, spacing = 14f,
        )!!
        val threeBounds = three.map { it.bounds }.reduce { a, c ->
            Bounds(minOf(a.minX, c.minX), minOf(a.minY, c.minY), 0f, maxOf(a.maxX, c.maxX), maxOf(a.maxY, c.maxY), maxOf(a.maxZ, c.maxZ))
        }

        data class Item(val o: SceneObject, val color: String)
        data class Scene(val name: String, val camera: OrbitCamera, val items: List<Item>)
        val scenes = listOf(
            Scene("empty-bed", overview, emptyList()),
            Scene("cube-close", OrbitCamera.framing(obj(1, cube).bounds, yaw = -1.1f, pitch = 0.45f), listOf(Item(obj(1, cube), "amber"))),
            // The M4 exit test layout: three objects arranged, the rotated cube selected.
            Scene("three-arranged", OrbitCamera.framing(threeBounds, yaw = -1.2f, pitch = 0.6f),
                three.map { Item(it, if (it.id == 2L) "selected" else "amber") }),
            Scene("three-overview", overview, three.map { Item(it, if (it.id == 2L) "selected" else "amber") }),
            // One dragged partly off the bed: red.
            Scene("off-the-bed", overview, listOf(Item(obj(1, cube, Transform(scale = 2f), x = 15f, y = 60f), "red"), Item(obj(2, cube), "amber"))),
            Scene("ybox-as-exported", OrbitCamera.framing(obj(1, ybox, Transform(scale = 2f)).bounds, yaw = -1.1f, pitch = 0.45f),
                listOf(Item(obj(1, ybox, Transform(scale = 2f)), "amber"))),
            // A loose part floating above the model: drawn in purple.
            Scene("floating-part", OrbitCamera.framing(obj(1, loose).bounds, yaw = -1.1f, pitch = 0.35f), listOf(Item(obj(1, loose), "amber"))),
            Scene("floating-part-selected", OrbitCamera.framing(obj(1, loose).bounds, yaw = -1.1f, pitch = 0.35f), listOf(Item(obj(1, loose), "selected"))),
            Scene("cube-lowest-view", OrbitCamera.framing(obj(1, cube).bounds, yaw = -1.1f, pitch = 0.45f).orbit(0f, -100000f, 800f),
                listOf(Item(obj(1, cube), "amber"))),
        )
        val json = scenes.joinToString(",\n", "[\n", "\n]") { s ->
            val viewProj = Mat4.mul(s.camera.projection(aspect), s.camera.view())
            val items = s.items.joinToString(",", "[", "]") { (o, color) ->
                // Same colouring as Viewport: floating parts in purple, the rest in the object's colour.
                val floating = o.floating.map { it.part }.toSet()
                val segs = o.mesh.parts.mapIndexed { i, p ->
                    val c = if (i in floating) (if (color == "selected") "floating_selected" else "floating") else color
                    """[${p.firstTriangle},${p.triangles},"$c"]"""
                }.joinToString(",", "[", "]")
                """{"mesh":"${o.mesh.stl.absolutePath}","model":${arr(o.modelMatrix())},"segments":$segs}"""
            }
            """  {"name":"${s.name}","bed":[${v.width},${v.depth},${v.height}],"viewProj":${arr(viewProj)},"view":${arr(s.camera.view())},"items":$items}"""
        }
        File("build").mkdirs()
        File("build/viewport-scenes.json").writeText(json)
        // The same three objects as the engine receives them, with the bounds the app
        // expects, for tools/check_placement.py.
        val placed = three.joinToString(",\n", "[\n", "\n]") { o ->
            val b = o.bounds
            """  {"mesh":"${o.mesh.stl.absolutePath}","matrix":"${o.transform.matrix()}","x":${o.x},"y":${o.y},""" +
                """"expected":[${b.minX},${b.minY},${b.minZ},${b.maxX},${b.maxY},${b.maxZ}]}"""
        }
        File("build/placed-objects.json").writeText(placed)
    }
}
