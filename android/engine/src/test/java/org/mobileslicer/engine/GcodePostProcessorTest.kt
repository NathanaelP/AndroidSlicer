// SPDX-License-Identifier: AGPL-3.0-or-later
package org.mobileslicer.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GcodePostProcessorTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Shaped like CuraEngine command-line output, with the heater wrongly turned off on layer 0. */
    private val sample = """
        ;FLAVOR:Marlin
        ;TIME:6666
        ;Filament used: 0m
        ;Layer height: 0.2
        ;MINX:2.14748e+06
        ;MINY:2.14748e+06
        ;MINZ:2.14748e+06
        ;MAXX:-2.14748e+06
        ;MAXY:-2.14748e+06
        ;MAXZ:-2.14748e+06
        ;TARGET_MACHINE.NAME:Test
        M104 S215
        M109 S215
        G28 ; home
        G92 E0
        G1 X1 Y20 Z0.3 F5000 ; prime line, not part of the model
        G1 X1 Y200 Z0.3 F1500 E15
        G92 E0
        ;LAYER_COUNT:2
        ;LAYER:0
        M104 S0
        G0 X100 Y100 Z0.2
        G1 X120 Y100 E1
        G1 X120 Y110 E2
        G1 E1.2 ; retract
        G0 X130 Y130
        ;TIME_ELAPSED:10
        ;LAYER:1
        M104 S210
        G0 Z0.4
        G0 X100 Y100
        G1 E2 ; unretract
        G1 X100 Y110 E3
        ;TIME_ELAPSED:20
        M104 S0
        M140 S0
        ;End of Gcode
    """.trimIndent()

    @Test fun fillsHeaderAndFlagsUnsafeTemperature() {
        val f = tmp.newFile("x.gcode").apply { writeText(sample + "\n") }
        val check = GcodePostProcessor.process(f, printTimeSeconds = 1234, filamentMeters = 0.5, minSafeNozzleTemp = 180.0)

        val lines = f.readLines()
        assertEquals(";TIME:1234", lines[1])
        assertEquals(";Filament used: 0.50000m", lines[2])
        // Model extrusions only: the prime line at X1 and the travel to X130 don't count.
        assertEquals(";MINX:100.000", lines[4])
        assertEquals(";MINY:100.000", lines[5])
        assertEquals(";MINZ:0.200", lines[6])
        assertEquals(";MAXX:120.000", lines[7])
        assertEquals(";MAXY:110.000", lines[8])
        assertEquals(";MAXZ:0.400", lines[9])
        assertEquals("the rest is unchanged", sample.lines().drop(10), lines.drop(10))

        // The layer-0 "M104 S0" is flagged; the start temperatures, S210 and the end G-code aren't.
        assertEquals(listOf(TemperatureProblem(0, 21, "M104 S0")), check.temperatureProblems)
    }

    @Test fun cleanFileHasNoProblems() {
        val f = tmp.newFile("y.gcode").apply { writeText(sample.replace("M104 S0\nG0 X100", "G0 X100") + "\n") }
        val check = GcodePostProcessor.process(f, 1, 0.1, 180.0)
        assertTrue(check.temperatureProblems.isEmpty())
    }

    /** Checks a real file; runs only when MOBILESLICER_GCODE points at one. */
    @Test fun checkRealFile() {
        val path = System.getenv("MOBILESLICER_GCODE") ?: return
        val f = tmp.newFile("real.gcode").apply { writeBytes(File(path).readBytes()) }
        val check = GcodePostProcessor.process(f, null, null, 180.0)
        println("GCODE CHECK: $check")
        println("GCODE HEADER: " + f.readLines().take(11).joinToString(" | "))
    }
}
