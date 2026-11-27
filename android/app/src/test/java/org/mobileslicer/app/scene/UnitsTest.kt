// SPDX-License-Identifier: AGPL-3.0-or-later
package org.mobileslicer.app.scene

import org.junit.Assert.assertEquals
import org.junit.Test
import org.mobileslicer.engine.Bounds
import org.mobileslicer.engine.MeshInfo
import org.mobileslicer.engine.Transform
import java.io.File

class UnitsTest {
    private val v = BuildVolume(235f, 230f, 270f)
    private fun obj(w: Float, d: Float, h: Float, scale: Float = 1f) =
        SceneObject(1, "m", MeshInfo(File("m.stl"), "STL", 12, Bounds(0f, 0f, 0f, w, d, h), 0), Transform(scale = scale), 100f, 100f)

    @Test fun tinyModelGetsGuesses() {
        // 4 x 3 x 6 units: cm gives 60 mm tall, inches 152 mm; metres would be 6 m.
        val g = obj(4f, 3f, 4.5f).unitGuesses(v)
        assertEquals(listOf("centimetres", "inches"), g.map { it.unit })
        assertEquals(45f, g[0].heightMm, 1e-3f)
    }

    @Test fun metresForAVeryTinyOne() {
        assertEquals(listOf("metres"), obj(0.05f, 0.04f, 0.08f).unitGuesses(v).map { it.unit })
    }

    @Test fun noGuessesForSensibleOrScaledModels() {
        assertEquals(emptyList<UnitGuess>(), obj(40f, 30f, 60f).unitGuesses(v))
        assertEquals(emptyList<UnitGuess>(), obj(4f, 3f, 4.5f, scale = 2f).unitGuesses(v))
    }
}
