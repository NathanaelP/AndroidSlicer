// SPDX-License-Identifier: AGPL-3.0-or-later
package org.mobileslicer.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.PI

class GcodePreviewTest {
    private fun parse(text: String, d: Float = 1.75f) = GcodePreview.parse(text.byteInputStream(), d)

    private fun seg(p: GcodePreview, i: Int): FloatArray {
        val b = p.segments.duplicate().order(p.segments.order())
        return FloatArray(GcodePreview.SEGMENT_FLOATS) { b.getFloat(i * GcodePreview.SEGMENT_BYTES + 4 * it) }
    }

    @Test fun layersTypesWidthsAndTimes() {
        val area = (PI * 1.75 * 1.75 / 4).toFloat()
        // 10 mm of a 0.4 mm wide, 0.2 mm high bead needs 0.8 mm^3 of filament.
        val e = 0.8f / area
        val g = """
            ;TIME:123
            G1 X0 Y0 E5 ; purge line before the first layer: not drawn
            G92 E0
            ;LAYER:0
            G0 Z0.2
            ;TYPE:WALL-OUTER
            G0 X10 Y10
            G1 X20 Y10 E$e
            ;TYPE:FILL
            G1 X20 Y20 E${2 * e}
            G1 E${2 * e - 0.5f} ; retract: no move
            G0 X30 Y30
            ;TIME_ELAPSED:10.5
            ;LAYER:1
            G0 Z0.4
            ;TYPE:SKIN
            G1 X30 Y40 E${3 * e}
            ;TIME_ELAPSED:20
            G91
            G1 X5 Y5 ; end G-code wipe: not part of the print
            G90
            ;End of Gcode
        """.trimIndent()
        val p = parse(g)
        assertEquals(2, p.layers.size)
        val (l0, l1) = p.layers
        assertEquals(0.2f, l0.z, 1e-6f); assertEquals(0.2f, l0.height, 1e-6f)
        assertEquals(0.4f, l1.z, 1e-6f); assertEquals(0.2f, l1.height, 1e-5f)
        assertEquals(10.5f, l0.elapsedSeconds); assertEquals(20f, l1.elapsedSeconds)
        assertEquals(listOf(0, 2, 2, 1), listOf(l0.firstSegment, l0.segmentCount, l1.firstSegment, l1.segmentCount))
        assertEquals(2, l0.travelCount)  // to the start, and away after the retract
        assertEquals(0, l1.travelCount)  // the end G-code's wipe is left out
        assertEquals(3, p.segmentCount)
        val s0 = seg(p, 0)
        assertEquals(listOf(10f, 10f, 20f, 10f, 0.2f), s0.take(5))
        assertEquals(0.2f, s0[5], 1e-6f)
        assertEquals(0.4f, s0[6], 1e-3f)  // width from the filament used
        assertEquals(MoveType.WALL_OUTER.ordinal.toFloat(), s0[7])
        assertEquals(MoveType.FILL.ordinal.toFloat(), seg(p, 1)[7])
        assertEquals(MoveType.SKIN.ordinal.toFloat(), seg(p, 2)[7])
        assertEquals(123f, p.totalSeconds)
        assertEquals(listOf(MoveType.WALL_OUTER, MoveType.SKIN, MoveType.FILL), p.typesPresent)
        assertEquals(10f, p.lengthByType[MoveType.WALL_OUTER.ordinal], 1e-4f)
        // Filament: three beads of e each; the retraction and the move after it add nothing.
        assertEquals(3 * e, p.filamentMm, 1e-4f)
    }

    @Test fun relativeExtrusion() {
        val p = parse(";LAYER:0\nM83\nG0 Z0.3\nG1 X10 Y0 E0.5\nG1 X20 Y0 E0.5\nG1 E-1\nG1 E1\nG1 X30 Y0 E0.5\n;TIME_ELAPSED:3\n")
        assertEquals(3, p.segmentCount)
        // 0.5 + 0.5, a retraction and the push back (no new filament), then 0.5.
        assertEquals(1.5f, p.filamentMm, 1e-5f)
    }

    @Test fun realCuraOutput() {
        // The 20 mm cube, sliced by the desktop engine with the Normal profile.
        val f = File(javaClass.classLoader!!.getResource("cube20-normal.gcode")!!.toURI())
        val p = GcodePreview.read(f, 1.75f)
        val layerLines = f.readLines().count { it.startsWith(";LAYER:") }
        assertEquals(layerLines, p.layers.size)
        val last = p.layers.last()
        assertEquals(20f, last.z, 0.15f)  // Cura slices each layer at its middle: the top layer is at 19.95
        assertTrue(p.layers.zipWithNext().all { (a, b) -> b.z > a.z && b.elapsedSeconds >= a.elapsedSeconds })
        // Every bead is inside the bed and has a sensible width.
        val b: ByteBuffer = p.segments
        for (i in 0 until p.segmentCount) {
            val s = seg(p, i)
            assertTrue(s.take(4).all { it in 0f..235f })
            assertTrue("width ${s[6]}", s[6] in 0.1f..1.5f)
        }
        assertTrue(MoveType.WALL_OUTER in p.typesPresent && MoveType.FILL in p.typesPresent && MoveType.SKIRT in p.typesPresent)
        // Filament agrees with the engine's own total within a fraction of a percent.
        val engineMm3 = 3304f
        val area = (PI * 1.75 * 1.75 / 4).toFloat()
        assertEquals(engineMm3, p.filamentMm * area, engineMm3 * 0.01f)
        println("cube: ${p.layers.size} layers, ${p.segmentCount} beads, ${p.travelCount} travels, ${b.limit()} bytes")
    }
}
