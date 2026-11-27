// SPDX-License-Identifier: AGPL-3.0-or-later
package org.mobileslicer.engine

import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sqrt

/** What an extrusion move prints, from Cura's ";TYPE:" comments. */
enum class MoveType(val cura: List<String>, val label: String) {
    WALL_OUTER(listOf("WALL-OUTER"), "Outer wall"),
    WALL_INNER(listOf("WALL-INNER"), "Inner walls"),
    SKIN(listOf("SKIN"), "Top and bottom"),
    FILL(listOf("FILL"), "Infill"),
    SUPPORT(listOf("SUPPORT", "SUPPORT-INTERFACE"), "Support"),
    SKIRT(listOf("SKIRT", "BRIM"), "Skirt and brim"),
    PRIME_TOWER(listOf("PRIME-TOWER"), "Prime tower"),
    OTHER(emptyList(), "Other");

    companion object {
        fun of(name: String): MoveType = entries.firstOrNull { name in it.cura } ?: OTHER
    }
}

/** One layer of the preview. Ranges index [GcodePreview.segments] and [GcodePreview.travels]. */
data class PreviewLayer(
    val z: Float,
    val height: Float,
    /** Print time when this layer ends, from the engine's estimate (seconds). */
    val elapsedSeconds: Float,
    /** Filament pushed by the end of this layer (mm of filament). */
    val filamentMm: Float,
    val firstSegment: Int,
    val segmentCount: Int,
    val firstTravel: Int,
    val travelCount: Int,
)

/**
 * The toolpaths of a sliced job, ready to draw. [segments] holds one record of
 * [SEGMENT_FLOATS] floats per extrusion move: x0, y0, x1, y1, z (top of the
 * bead), layer height, width, MoveType ordinal. [travels] holds x, y, z pairs:
 * one line per travel move. Both are direct buffers (outside the Java heap),
 * in layer order, ready to upload.
 */
class GcodePreview(
    val layers: List<PreviewLayer>,
    val segments: ByteBuffer,
    val segmentCount: Int,
    val travels: ByteBuffer,
    val travelCount: Int,
    /** Extruded length (mm of nozzle travel) per MoveType. */
    val lengthByType: FloatArray,
    /** Print time estimate from the G-code header (seconds), if there is one. */
    val totalSeconds: Float?,
    val filamentMm: Float,
) {
    val typesPresent: List<MoveType> get() = MoveType.entries.filter { lengthByType[it.ordinal] > 0f }

    companion object {
        const val SEGMENT_FLOATS = 8
        const val SEGMENT_BYTES = SEGMENT_FLOATS * 4
        const val TRAVEL_BYTES = 6 * 4

        fun read(file: File, filamentDiameter: Float): GcodePreview =
            file.inputStream().use { parse(it, filamentDiameter) }

        /**
         * Parses Cura G-code: G0/G1 moves (absolute or relative positioning,
         * absolute or relative extrusion, G92 resets), ";LAYER:", ";TYPE:" and
         * ";TIME_ELAPSED:" comments. Moves before the first layer (the purge line in
         * the start G-code) and after a layer's time stamp until the next layer (the
         * end G-code) are left out.
         */
        fun parse(input: InputStream, filamentDiameter: Float): GcodePreview = Parser(filamentDiameter).run(input)
    }
}

private class Parser(filamentDiameter: Float) {
    private val filamentArea = (PI * filamentDiameter * filamentDiameter / 4.0).toFloat()

    // Machine state.
    private var x = 0f; private var y = 0f; private var z = 0f; private var e = 0f
    /** Highest E since the last reset: pushing back filament after a retraction isn't new filament. */
    private var eHigh = 0f
    private var relativeXyz = false
    private var relativeE = false
    private var type = MoveType.OTHER

    // Output.
    private var seg = ByteBuffer.allocateDirect(1 shl 20).order(ByteOrder.nativeOrder())
    private var segCount = 0
    private var trav = ByteBuffer.allocateDirect(1 shl 18).order(ByteOrder.nativeOrder())
    private var travCount = 0
    private val layers = ArrayList<PreviewLayer>()
    private val lengthByType = FloatArray(MoveType.entries.size)
    private var totalSeconds: Float? = null
    private var filament = 0f

    // The layer being read.
    private var inLayers = false
    private var finished = false
    private var layerZ = Float.NaN
    private var prevLayerZ = 0f
    private var layerStartSeg = 0
    private var layerStartTrav = 0
    private var layerElapsed = 0f

    fun run(input: InputStream): GcodePreview {
        val stream = BufferedInputStream(input, 1 shl 16)
        val line = ByteArray(4096)
        var n = 0
        while (true) {
            val b = stream.read()
            if (b < 0 || b == '\n'.code) {
                handle(line, n)
                n = 0
                if (b < 0) break
            } else if (n < line.size) {
                line[n++] = b.toByte()
            }
        }
        closeLayer()  // a file cut short has no time stamp on its last layer
        seg.flip(); trav.flip()
        return GcodePreview(layers, seg, segCount, trav, travCount, lengthByType, totalSeconds, filament)
    }

    private fun handle(l: ByteArray, len: Int) {
        var end = len
        if (end > 0 && l[end - 1] == '\r'.code.toByte()) end--
        if (end == 0) return
        if (l[0] == ';'.code.toByte()) { comment(l, end); return }
        if (finished) return
        // Strip a trailing comment.
        var codeEnd = end
        for (i in 0 until end) if (l[i] == ';'.code.toByte()) { codeEnd = i; break }
        if (codeEnd < 2) return
        val letter = l[0].toInt().toChar()
        val num = parseIntAt(l, 1, codeEnd)
        when {
            letter == 'G' && (num == 0 || num == 1) -> move(l, codeEnd)
            letter == 'G' && num == 90 -> relativeXyz = false
            letter == 'G' && num == 91 -> relativeXyz = true
            letter == 'G' && num == 92 -> {
                // Set position; Cura uses it to reset E.
                forEachWord(l, codeEnd) { c, v ->
                    when (c) { 'X' -> x = v; 'Y' -> y = v; 'Z' -> z = v; 'E' -> { e = v; eHigh = v } }
                }
            }
            letter == 'M' && num == 82 -> relativeE = false
            letter == 'M' && num == 83 -> relativeE = true
        }
    }

    private fun comment(l: ByteArray, end: Int) {
        when {
            startsWith(l, end, ";LAYER:") -> {
                if (finished) return
                closeLayer()
                inLayers = true
                layerStartSeg = segCount; layerStartTrav = travCount
            }
            startsWith(l, end, ";TYPE:") -> type = MoveType.of(String(l, 6, end - 6, Charsets.US_ASCII).trim())
            startsWith(l, end, ";TIME_ELAPSED:") -> {
                // Cura writes this at the end of each layer: anything after it, up to the
                // next layer, is the end G-code (retract, park), not part of the print.
                if (inLayers) { layerElapsed = parseFloatAt(l, 14, end); closeLayer(); inLayers = false }
            }
            startsWith(l, end, ";TIME:") && totalSeconds == null -> totalSeconds = parseFloatAt(l, 6, end)
            startsWith(l, end, ";End of Gcode") -> { closeLayer(); finished = true }
        }
    }

    private fun move(l: ByteArray, end: Int) {
        var nx = x; var ny = y; var nz = z; var ne = e
        var hasE = false
        forEachWord(l, end) { c, v ->
            when (c) {
                'X' -> nx = if (relativeXyz) x + v else v
                'Y' -> ny = if (relativeXyz) y + v else v
                'Z' -> nz = if (relativeXyz) z + v else v
                'E' -> { ne = if (relativeE) e + v else v; hasE = true }
            }
        }
        val dE = if (hasE) ne - e else 0f
        val dx = nx - x; val dy = ny - y
        val moved = dx != 0f || dy != 0f
        if (inLayers && !finished && moved) {
            if (dE > 0f) {
                if (layerZ.isNaN()) layerZ = nz
                val length = sqrt(dx * dx + dy * dy)
                val h = maxOf(layerHeight(), 0.01f)
                // Bead cross-section as a rectangle, as Cura plans it: width = volume / length / height.
                val w = (dE * filamentArea / length / h).coerceIn(0.05f, 3f)
                putSegment(x, y, nx, ny, nz, h, w, type)
                lengthByType[type.ordinal] += length
            } else {
                putTravel(x, y, z, nx, ny, nz)
            }
        }
        if (ne > eHigh) {
            if (inLayers && !finished) filament += ne - eHigh
            eHigh = ne
        }
        x = nx; y = ny; z = nz; e = ne
    }

    private fun layerHeight(): Float = if (layerZ.isNaN()) z - prevLayerZ else layerZ - prevLayerZ

    private fun closeLayer() {
        if (!inLayers) return
        inLayers = false
        val zz = if (layerZ.isNaN()) z else layerZ
        val segs = segCount - layerStartSeg
        val travs = travCount - layerStartTrav
        if (segs > 0 || travs > 0) {
            layers += PreviewLayer(zz, zz - prevLayerZ, layerElapsed, filament, layerStartSeg, segs, layerStartTrav, travs)
            if (segs > 0) prevLayerZ = zz
        }
        layerZ = Float.NaN
        layerStartSeg = segCount
        layerStartTrav = travCount
    }

    private fun putSegment(x0: Float, y0: Float, x1: Float, y1: Float, z: Float, h: Float, w: Float, t: MoveType) {
        if (seg.remaining() < GcodePreview.SEGMENT_BYTES) seg = grow(seg)
        seg.putFloat(x0).putFloat(y0).putFloat(x1).putFloat(y1).putFloat(z).putFloat(h).putFloat(w).putFloat(t.ordinal.toFloat())
        segCount++
    }

    private fun putTravel(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float) {
        if (trav.remaining() < GcodePreview.TRAVEL_BYTES) trav = grow(trav)
        trav.putFloat(x0).putFloat(y0).putFloat(z0).putFloat(x1).putFloat(y1).putFloat(z1)
        travCount++
    }

    private fun grow(b: ByteBuffer): ByteBuffer {
        val n = ByteBuffer.allocateDirect(b.capacity() * 2).order(ByteOrder.nativeOrder())
        b.flip()
        n.put(b)
        return n
    }

    // ---- small parsing helpers, no allocation per line

    private fun startsWith(l: ByteArray, end: Int, p: String): Boolean {
        if (end < p.length) return false
        for (i in p.indices) if (l[i] != p[i].code.toByte()) return false
        return true
    }

    /** Calls [f] with each letter and number after the command word (such as X12.5). */
    private inline fun forEachWord(l: ByteArray, end: Int, f: (Char, Float) -> Unit) {
        var i = 0
        while (i < end && l[i] != ' '.code.toByte()) i++  // skip G1
        while (i < end) {
            val c = l[i].toInt().toChar()
            if (c in 'A'..'Z') {
                var j = i + 1
                while (j < end && l[j] != ' '.code.toByte()) j++
                if (j > i + 1) f(c, parseFloatAt(l, i + 1, j))
                i = j
            } else i++
        }
    }

    private fun parseIntAt(l: ByteArray, from: Int, end: Int): Int {
        var v = 0
        var i = from
        while (i < end && l[i] in '0'.code.toByte()..'9'.code.toByte()) { v = v * 10 + (l[i] - '0'.code.toByte()); i++ }
        return if (i == from) -1 else v
    }

    private fun parseFloatAt(l: ByteArray, from: Int, end: Int): Float {
        var i = from
        while (i < end && l[i] == ' '.code.toByte()) i++
        var neg = false
        if (i < end && (l[i] == '-'.code.toByte() || l[i] == '+'.code.toByte())) { neg = l[i] == '-'.code.toByte(); i++ }
        var whole = 0.0
        while (i < end && l[i] in '0'.code.toByte()..'9'.code.toByte()) { whole = whole * 10 + (l[i] - '0'.code.toByte()); i++ }
        if (i < end && l[i] == '.'.code.toByte()) {
            i++
            var scale = 0.1
            while (i < end && l[i] in '0'.code.toByte()..'9'.code.toByte()) { whole += (l[i] - '0'.code.toByte()) * scale; scale *= 0.1; i++ }
        }
        return (if (neg) -whole else whole).toFloat()
    }
}
