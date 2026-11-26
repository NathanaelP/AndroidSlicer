// SPDX-License-Identifier: AGPL-3.0-or-later
package org.mobileslicer.app.scene

import org.mobileslicer.engine.Bounds
import org.mobileslicer.engine.MeshInfo
import org.mobileslicer.engine.PlacedObject
import org.mobileslicer.engine.Transform
import org.mobileslicer.engine.transformedBounds

/** The printer's build volume in mm; the bed's origin is its front-left corner. */
data class BuildVolume(val width: Float, val depth: Float, val height: Float)

/**
 * One object on the bed. [x] and [y] are where the centre of its footprint sits
 * (bed coordinates, mm). It always rests on the bed: there is no height offset,
 * as in Cura with "drop down to build plate" on.
 */
data class SceneObject(
    val id: Long,
    val name: String,
    val mesh: MeshInfo,
    val transform: Transform,
    val x: Float,
    val y: Float,
) {
    /** Bounds after the transform, before moving it onto the bed. */
    val localBounds: Bounds by lazy { transformedBounds(mesh, transform) }

    /** Parts that would print in mid-air (see [floatingParts]); worked out once per object state. */
    val floating: List<FloatingPart> by lazy { floatingParts() }

    /** Bounds where it sits on the bed. */
    val bounds: Bounds by lazy {
        val b = localBounds
        val dx = x - (b.minX + b.maxX) / 2f
        val dy = y - (b.minY + b.maxY) / 2f
        val dz = -b.minZ
        Bounds(b.minX + dx, b.minY + dy, b.minZ + dz, b.maxX + dx, b.maxY + dy, b.maxZ + dz)
    }

    /** Column-major 4x4 model matrix for drawing: transform, then onto the bed. */
    fun modelMatrix(): FloatArray {
        val b = localBounds
        val tx = x - (b.minX + b.maxX) / 2f
        val ty = y - (b.minY + b.maxY) / 2f
        val tz = -b.minZ
        val r = transform.rotation
        val s = transform.scale
        return floatArrayOf(
            s * r[0], s * r[3], s * r[6], 0f,
            s * r[1], s * r[4], s * r[7], 0f,
            s * r[2], s * r[5], s * r[8], 0f,
            tx, ty, tz, 1f,
        )
    }

    fun fits(v: BuildVolume, margin: Float = 0f): Boolean = bounds.let {
        it.minX >= margin - EPS && it.minY >= margin - EPS && it.maxX <= v.width - margin + EPS &&
            it.maxY <= v.depth - margin + EPS && it.maxZ <= v.height + EPS
    }

    /**
     * As the engine gets it. A mesh with holes left gets the engine's extensive
     * stitching, which joins the open ends of each layer's outlines so walls
     * with gaps still print.
     */
    fun toPlaced() = PlacedObject(
        mesh.stl, transform, x, y,
        if (mesh.health.checked && !mesh.health.closed) mapOf("meshfix_extensive_stitching" to "True") else emptyMap(),
    )

    companion object { private const val EPS = 1e-3f }
}

/** True if the footprints of [a] and [b] overlap. */
fun overlaps(a: SceneObject, b: SceneObject): Boolean {
    val p = a.bounds; val q = b.bounds
    return p.minX < q.maxX && q.minX < p.maxX && p.minY < q.maxY && q.minY < p.maxY
}

data class Scene(val objects: List<SceneObject> = emptyList(), val selectedId: Long? = null) {
    val selected: SceneObject? get() = objects.firstOrNull { it.id == selectedId }

    fun update(id: Long, change: (SceneObject) -> SceneObject): Scene =
        copy(objects = objects.map { if (it.id == id) change(it) else it })

    /** Pairs of object names whose footprints overlap. */
    fun overlapping(): List<Pair<SceneObject, SceneObject>> =
        objects.indices.flatMap { i -> (i + 1 until objects.size).map { objects[i] to objects[it] } }
            .filter { (a, b) -> overlaps(a, b) }
}

/**
 * Lays objects out in rows on the bed, largest first, [spacing] mm apart, and
 * centres the arrangement. Returns null if they don't all fit.
 */
fun arrange(objects: List<SceneObject>, v: BuildVolume, spacing: Float): List<SceneObject>? {
    if (objects.isEmpty()) return objects
    data class Slot(val obj: SceneObject, val w: Float, val d: Float, var x: Float = 0f, var y: Float = 0f)
    val slots = objects.map { o -> o.localBounds.let { Slot(o, it.sizeX, it.sizeY) } }
        .sortedWith(compareByDescending<Slot> { it.d }.thenByDescending { it.w })
    // Shelf packing: fill rows left to right, then start a new row behind.
    var rowY = 0f; var rowDepth = 0f; var cursorX = 0f; var usedWidth = 0f
    for (s in slots) {
        if (cursorX > 0f && cursorX + s.w > v.width) {
            rowY += rowDepth + spacing; rowDepth = 0f; cursorX = 0f
        }
        if (s.w > v.width) return null
        s.x = cursorX + s.w / 2f
        s.y = rowY + s.d / 2f
        cursorX += s.w + spacing
        usedWidth = maxOf(usedWidth, cursorX - spacing)
        rowDepth = maxOf(rowDepth, s.d)
    }
    val usedDepth = rowY + rowDepth
    if (usedDepth > v.depth) return null
    // Centre the whole arrangement on the bed.
    val ox = (v.width - usedWidth) / 2f
    val oy = (v.depth - usedDepth) / 2f
    val placed = slots.associate { it.obj.id to it.obj.copy(x = it.x + ox, y = it.y + oy) }
    return objects.map { placed.getValue(it.id) }
}

/**
 * Where a new object goes: the middle of the bed if that's free, otherwise
 * everything is re-arranged. Returns the new object list.
 */
fun placeNew(existing: List<SceneObject>, new: SceneObject, v: BuildVolume, spacing: Float): List<SceneObject> {
    val centred = new.copy(x = v.width / 2f, y = v.depth / 2f)
    if (existing.none { overlaps(it, centred) }) return existing + centred
    return arrange(existing + centred, v, spacing) ?: (existing + centred)
}

/** Undo and redo for immutable snapshots. */
class History<T>(private val limit: Int = 100) {
    private val undo = ArrayDeque<T>()
    private val redo = ArrayDeque<T>()
    private var lastKey: String? = null

    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    /**
     * Records [before], the state before a change. Consecutive changes with the
     * same non-null [key] (such as typing into one field) merge into one step.
     */
    fun record(before: T, key: String? = null) {
        if (key != null && key == lastKey && undo.isNotEmpty()) return
        undo.addLast(before)
        while (undo.size > limit) undo.removeFirst()
        redo.clear()
        lastKey = key
    }

    fun undo(current: T): T? = undo.removeLastOrNull()?.also { redo.addLast(current); lastKey = null }

    fun redo(current: T): T? = redo.removeLastOrNull()?.also { undo.addLast(current); lastKey = null }

    /** Ends merging, so the next change starts a new step. */
    fun breakMerge() { lastKey = null }
}
