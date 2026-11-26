// SPDX-License-Identifier: AGPL-3.0-or-later
package org.mobileslicer.app.scene

/** A guess at the units a file was saved in: scale by [factor] to get millimetres. */
data class UnitGuess(val unit: String, val factor: Float, val heightMm: Float)

/**
 * Other units this object's file might be in, when it's tiny as imported (under
 * [tinyMm] in every direction, at 100%). Each guess makes it at least [minMm]
 * across and still fits the build volume. STL and OBJ carry no units; most
 * slicers read them as millimetres, while CAD and sculpting apps often write
 * centimetres, inches or metres.
 */
fun SceneObject.unitGuesses(v: BuildVolume, tinyMm: Float = 5f, minMm: Float = 10f): List<UnitGuess> {
    if (transform.scale != 1f) return emptyList()
    val b = localBounds
    if (maxOf(b.sizeX, b.sizeY, b.sizeZ) >= tinyMm) return emptyList()
    return listOf("centimetres" to 10f, "inches" to 25.4f, "metres" to 1000f).mapNotNull { (unit, f) ->
        val x = b.sizeX * f; val y = b.sizeY * f; val z = b.sizeZ * f
        if (maxOf(x, y, z) >= minMm && x <= v.width && y <= v.depth && z <= v.height) UnitGuess(unit, f, z) else null
    }
}
