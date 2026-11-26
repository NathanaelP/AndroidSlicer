// SPDX-License-Identifier: AGPL-3.0-or-later
package org.mobileslicer.app.scene

import org.mobileslicer.engine.Bounds
import org.mobileslicer.engine.transformedBounds
import kotlin.math.sqrt

/** A part of an object that would print in mid-air: nothing under it, not even another part. */
data class FloatingPart(
    /** Index into the object's MeshInfo.parts. */
    val part: Int,
    /** How far above the bed its lowest point is, in mm. */
    val height: Float,
    val triangles: Int,
)

/**
 * The parts of this object that float: their lowest point is more than
 * [threshold] mm above the bed, and no other part of the object meets them.
 *
 * A part counts as attached when another part that reaches lower has surface
 * inside its bounding box (grown by one sample cell plus [threshold]): it rests
 * on it, is pushed into it, or sticks out of it. So an eye pushed into a head,
 * or a hat sitting on one, is attached, while a loose piece above the model is
 * not. It works from the mesh's surface sample points, so it is an estimate:
 * very thin contacts can be missed.
 */
fun SceneObject.floatingParts(threshold: Float = 0.3f): List<FloatingPart> {
    val parts = mesh.parts
    if (parts.size < 2) return emptyList()
    val b = localBounds
    val dx = x - (b.minX + b.maxX) / 2f
    val dy = y - (b.minY + b.maxY) / 2f
    val dz = -b.minZ
    // Each part's bounds on the bed.
    val placed = parts.map { p ->
        val pb = transformedBounds(mesh, p, transform)
        Bounds(pb.minX + dx, pb.minY + dy, pb.minZ + dz, pb.maxX + dx, pb.maxY + dy, pb.maxZ + dz)
    }
    // How close counts as touching: one sample cell (scaled) plus the threshold.
    val m = mesh.bounds
    val cell = sqrt(m.sizeX * m.sizeX + m.sizeY * m.sizeY + m.sizeZ * m.sizeZ) / 400f * transform.scale
    val touch = cell + threshold

    // Sample points of each part on the bed, computed when first needed.
    val out = FloatArray(3)
    val pointsOf = HashMap<Int, FloatArray>()
    fun points(i: Int): FloatArray = pointsOf.getOrPut(i) {
        val p = parts[i]
        FloatArray(3 * p.sampleCount).also { pts ->
            for (k in 0 until p.sampleCount) {
                val o = 3 * (p.sampleStart + k)
                transform.apply(mesh.samples[o], mesh.samples[o + 1], mesh.samples[o + 2], out)
                pts[3 * k] = out[0] + dx; pts[3 * k + 1] = out[1] + dy; pts[3 * k + 2] = out[2] + dz
            }
        }
    }

    val result = ArrayList<FloatingPart>()
    for ((i, pb) in placed.withIndex()) {
        if (pb.minZ <= threshold) continue
        val restsOnAnother = placed.indices.any { j ->
            if (j == i) return@any false
            val q = placed[j]
            // It must reach lower than this part, and its box must meet this part's box.
            if (q.minZ >= pb.minZ - 1e-4f || q.maxZ < pb.minZ - touch) return@any false
            if (q.maxX < pb.minX - touch || q.minX > pb.maxX + touch || q.maxY < pb.minY - touch || q.minY > pb.maxY + touch) return@any false
            // Then look for its surface inside this part's (grown) box.
            val pts = points(j)
            var k = 0
            var found = false
            while (k < pts.size && !found) {
                val px = pts[k]; val py = pts[k + 1]; val pz = pts[k + 2]
                found = pz >= pb.minZ - touch && pz <= pb.maxZ + touch &&
                    px >= pb.minX - touch && px <= pb.maxX + touch && py >= pb.minY - touch && py <= pb.maxY + touch
                k += 3
            }
            found
        }
        if (!restsOnAnother) result += FloatingPart(i, pb.minZ, parts[i].triangles)
    }
    return result
}
