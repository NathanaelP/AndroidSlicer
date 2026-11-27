// SPDX-License-Identifier: AGPL-3.0-or-later
package org.mobileslicer.engine

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The M5 exit test's broken meshes, imported the way the app does: edge check,
 * then Fill holes where there are holes. Reads build/broken (written by
 * tools/broken_meshes.py) and writes the app's meshes plus a summary to
 * build/broken-out, for tools/slice_broken.sh. Skipped when there is no input.
 */
class BrokenMeshesTest {
    @Test fun importAndRepair() {
        val dir = File("build/broken")
        assumeTrue(dir.isDirectory)
        val out = File("build/broken-out").apply { deleteRecursively(); mkdirs() }
        val summary = StringBuilder()
        for (f in dir.listFiles { x -> x.name.endsWith(".stl") }!!.sortedBy { it.name }) {
            val name = f.nameWithoutExtension
            val m = f.inputStream().use { MeshImporter.import(it, f.name, File(out, "$name.stl")) }
            val h = m.health
            summary.append("$name: ${m.triangles} triangles (${m.skippedTriangles} skipped), ${m.parts.size} parts, ")
                .append("open ${h.openEdges}, holes ${h.holes.size}, untraced ${h.untracedOpenEdges}, ")
                .append("non-manifold ${h.nonManifoldEdges}, flipped ${h.flippedEdges}, flat ${h.degenerateTriangles}")
            if (h.holes.isNotEmpty()) {
                val fixed = MeshRepair.fillHoles(m, File(out, "$name-filled.stl"))
                val g = fixed.health
                summary.append(" | filled: +${fixed.triangles - m.triangles} triangles, open ${g.openEdges}, holes ${g.holes.size}, ")
                    .append("non-manifold ${g.nonManifoldEdges}, flipped ${g.flippedEdges}")
            }
            summary.append("\n")
        }
        File(out, "summary.txt").writeText(summary.toString())
        print(summary)
    }
}
