// SPDX-License-Identifier: AGPL-3.0-or-later
package org.mobileslicer.engine

import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** A nozzle temperature set mid-print that's too low to print with. */
data class TemperatureProblem(val layer: Int?, val line: Int, val command: String)

data class GcodeCheck(
    val minX: Double, val minY: Double, val minZ: Double,
    val maxX: Double, val maxY: Double, val maxZ: Double,
    val temperatureProblems: List<TemperatureProblem>,
)

/**
 * Finishes CuraEngine's command-line output the way desktop Cura does, and checks it.
 *
 * In command-line mode the engine leaves placeholders in the header (;TIME:6666,
 * ;Filament used: 0m, MIN/MAX of ±2147483647). Printer software such as Moonraker
 * and Fluidd reads these, so the real values are written in. It also checks that
 * no nozzle temperature between the first layer and the end of the print drops
 * below a safe minimum, which catches profile settings gone wrong.
 */
object GcodePostProcessor {

    /**
     * Rewrites [gcode] in place with a filled-in header and returns what it found.
     * [minSafeNozzleTemp] is the lowest temperature allowed while printing.
     */
    fun process(gcode: File, printTimeSeconds: Long?, filamentMeters: Double?, minSafeNozzleTemp: Double): GcodeCheck {
        val check = scan(gcode, minSafeNozzleTemp)
        val tmp = File(gcode.parentFile, gcode.name + ".tmp")
        gcode.bufferedReader().use { input ->
            tmp.bufferedWriter().use { out ->
                var lineNo = 0
                while (true) {
                    val line = input.readLine() ?: break
                    lineNo++
                    out.write(if (lineNo <= HEADER_LINES) headerLine(line, check, printTimeSeconds, filamentMeters) else line)
                    out.newLine()
                }
            }
        }
        if (!tmp.renameTo(gcode)) {
            tmp.copyTo(gcode, overwrite = true); tmp.delete()
        }
        return check
    }

    private fun headerLine(line: String, c: GcodeCheck, time: Long?, meters: Double?): String = when {
        line.startsWith(";TIME:") && time != null -> ";TIME:$time"
        line.startsWith(";Filament used:") && meters != null -> ";Filament used: ${fmt(meters, 5)}m"
        line.startsWith(";MINX:") -> ";MINX:${fmt(c.minX, 3)}"
        line.startsWith(";MINY:") -> ";MINY:${fmt(c.minY, 3)}"
        line.startsWith(";MINZ:") -> ";MINZ:${fmt(c.minZ, 3)}"
        line.startsWith(";MAXX:") -> ";MAXX:${fmt(c.maxX, 3)}"
        line.startsWith(";MAXY:") -> ";MAXY:${fmt(c.maxY, 3)}"
        line.startsWith(";MAXZ:") -> ";MAXZ:${fmt(c.maxZ, 3)}"
        else -> line
    }

    private fun fmt(v: Double, decimals: Int) = String.format(Locale.ROOT, "%.${decimals}f", v)

    /**
     * Finds the extent of the model's extrusions (from ;LAYER:0 on, so the prime
     * line in the start G-code isn't counted) and any unsafe nozzle temperature
     * set between ;LAYER:0 and the last ;TIME_ELAPSED line, where the end G-code
     * begins.
     */
    fun scan(gcode: File, minSafeNozzleTemp: Double): GcodeCheck {
        // First pass: where the end of the print is.
        var lastTimeElapsed = -1
        gcode.bufferedReader().useLines { lines ->
            lines.forEachIndexed { i, l -> if (l.startsWith(";TIME_ELAPSED:")) lastTimeElapsed = i }
        }

        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var minZ = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE; var maxZ = -Double.MAX_VALUE
        var x = 0.0; var y = 0.0; var z = 0.0; var e = 0.0
        var relativeE = false
        var layer: Int? = null
        val problems = mutableListOf<TemperatureProblem>()

        gcode.bufferedReader().useLines { lines ->
            lines.forEachIndexed { i, raw ->
                if (raw.startsWith(";LAYER:")) {
                    layer = raw.substringAfter(':').trim().toIntOrNull() ?: layer
                    return@forEachIndexed
                }
                val line = raw.substringBefore(';').trim()
                if (line.isEmpty()) return@forEachIndexed
                val words = line.split(' ', '\t').filter { it.isNotEmpty() }
                when (words[0]) {
                    "M82" -> relativeE = false
                    "M83" -> relativeE = true
                    "G92" -> words.drop(1).forEach { w -> if (w[0] == 'E') e = w.substring(1).toDoubleOrNull() ?: e }
                    "M104", "M109" -> {
                        val s = words.firstOrNull { it.startsWith("S") }?.substring(1)?.toDoubleOrNull()
                        if (s != null && layer != null && i < lastTimeElapsed && s < minSafeNozzleTemp) {
                            problems += TemperatureProblem(layer, i + 1, line)
                        }
                    }
                    "G0", "G1" -> {
                        var nx = x; var ny = y; var nz = z; var ne: Double? = null
                        for (w in words.drop(1)) {
                            val v = w.substring(1).toDoubleOrNull() ?: continue
                            when (w[0]) { 'X' -> nx = v; 'Y' -> ny = v; 'Z' -> nz = v; 'E' -> ne = v }
                        }
                        val extruding = ne != null && (if (relativeE) ne!! > 0 else ne!! > e) && (nx != x || ny != y)
                        if (extruding && layer != null) {
                            minX = min(minX, min(x, nx)); maxX = max(maxX, max(x, nx))
                            minY = min(minY, min(y, ny)); maxY = max(maxY, max(y, ny))
                            minZ = min(minZ, nz); maxZ = max(maxZ, nz)
                        }
                        if (ne != null) e = if (relativeE) e + ne!! else ne!!
                        x = nx; y = ny; z = nz
                    }
                }
            }
        }
        if (minX == Double.MAX_VALUE) { minX = 0.0; minY = 0.0; minZ = 0.0; maxX = 0.0; maxY = 0.0; maxZ = 0.0 }
        return GcodeCheck(minX, minY, minZ, maxX, maxY, maxZ, problems)
    }

    private const val HEADER_LINES = 20
}
