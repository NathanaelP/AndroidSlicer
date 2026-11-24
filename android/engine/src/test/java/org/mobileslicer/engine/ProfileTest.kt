// SPDX-License-Identifier: AGPL-3.0-or-later
package org.mobileslicer.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Parses the profiles bundled in the app, built by profiles/build_profiles.py. */
class ProfileTest {
    private val profiles = File("src/main/assets/profiles").listFiles { f -> f.extension == "json" }!!
        .map { Profile.parseJson(it.readText()) }
        .sortedBy { it.float("layer_height") }

    @Test fun threeQualityPresets() {
        assertEquals(listOf("Fine", "Normal", "Fast"), profiles.map { it.quality })
        assertEquals(listOf(0.1f, 0.15f, 0.2f), profiles.map { it.float("layer_height") })
    }

    @Test fun completeAndSafe() {
        for (p in profiles) {
            assertTrue(p.id, p.settings.size > 700)
            assertEquals("ELEGOO NEPTUNE 4 Pro", p.machine)
            assertEquals("Generic PLA", p.material)
            assertEquals(1.24, p.materialDensity, 1e-9)
            assertEquals(235f, p.float("machine_width"))
            // The M1 bug: this must follow the print temperature, not fall back to 0.
            assertEquals(p.float("material_print_temperature"), p.float("cool_min_temperature"))
            // Placeholders in Elegoo's start G-code are filled in, with real line breaks.
            val start = p["machine_start_gcode"]!!
            assertFalse(start.contains("{"))
            assertTrue(start.lines().any { it.startsWith("M109 S210") })
        }
    }
}
