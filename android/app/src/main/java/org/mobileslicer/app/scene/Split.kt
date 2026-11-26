// SPDX-License-Identifier: AGPL-3.0-or-later
package org.mobileslicer.app.scene

import org.mobileslicer.engine.MeshInfo
import org.mobileslicer.engine.MeshParts
import org.mobileslicer.engine.transformedBounds

/**
 * How [SceneObject] would split: groups of part indices, where parts that touch
 * (within [gap] mm, at the object's scale) stay together. One group means there
 * is nothing to split.
 */
fun SceneObject.splitGroups(gap: Float = 0.3f): List<List<Int>> =
    MeshParts.touchingGroups(mesh, gap / transform.scale)

/**
 * New objects for [pieces] (meshes cut from this object, see MeshParts.extract),
 * with ids from [ids]. Each keeps this object's scale and rotation and stays
 * where it was over the bed; like every object, it then rests on the bed, so a
 * piece that was floating drops down.
 */
fun SceneObject.splitInto(pieces: List<MeshInfo>, ids: List<Long>): List<SceneObject> {
    val b = localBounds
    val dx = x - (b.minX + b.maxX) / 2f
    val dy = y - (b.minY + b.maxY) / 2f
    val base = name.substringBeforeLast('.')
    val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
    return pieces.mapIndexed { i, m ->
        val pb = transformedBounds(m, transform)
        SceneObject(ids[i], "$base (${i + 1})$ext", m, transform, (pb.minX + pb.maxX) / 2f + dx, (pb.minY + pb.maxY) / 2f + dy)
    }
}
