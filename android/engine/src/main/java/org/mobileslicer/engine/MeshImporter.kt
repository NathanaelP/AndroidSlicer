// SPDX-License-Identifier: AGPL-3.0-or-later
package org.mobileslicer.engine

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Axis-aligned bounding box of a mesh, in the mesh's own units. */
data class Bounds(
    val minX: Float, val minY: Float, val minZ: Float,
    val maxX: Float, val maxY: Float, val maxZ: Float,
) {
    val sizeX get() = maxX - minX
    val sizeY get() = maxY - minY
    val sizeZ get() = maxZ - minZ
}

/** Result of importing a mesh: a clean binary STL plus what we learned on the way. */
data class MeshInfo(
    val stl: File,
    val sourceFormat: String,
    val triangles: Long,
    val bounds: Bounds,
    /** Triangles dropped because they had a non-finite coordinate or a bad vertex index. */
    val skippedTriangles: Long,
)

class MeshImportException(message: String) : IOException(message)

/**
 * Converts STL (ASCII or binary) and OBJ into a single binary STL that CuraEngine
 * loads quickly and reliably. Streams the input, so large sculpts don't have to fit
 * in memory as text; OBJ vertex positions are kept in a float array.
 *
 * Why not hand OBJ straight to CuraEngine: its OBJ reader skips vertex lines with
 * extra values (such as Nomad's vertex colours) or exponents, which corrupts the
 * face indices, and it parses with regular expressions, which is very slow on
 * multi-million-polygon meshes.
 */
object MeshImporter {

    /** Called with the number of input bytes read so far. */
    fun interface Progress { fun onBytes(read: Long) }

    fun import(input: InputStream, fileName: String, out: File, progress: Progress = Progress {}): MeshInfo {
        val counting = CountingInputStream(BufferedInputStream(input, 1 shl 16), progress)
        val head = ByteArray(84)
        counting.mark(head.size)
        val headLen = readFully(counting, head, 0, head.size)
        counting.reset()

        val lower = fileName.lowercase()
        return StlWriter(out).use { writer ->
            val format = when {
                lower.endsWith(".obj") -> { readObj(counting, writer); "OBJ" }
                lower.endsWith(".stl") || looksLikeStl(head, headLen) ->
                    if (isAsciiStl(head, headLen)) { readAsciiStl(counting, writer); "STL (ASCII)" }
                    else { readBinaryStl(counting, writer); "STL (binary)" }
                else -> throw MeshImportException("Unsupported file type. Pick an .stl or .obj file.")
            }
            if (writer.triangles == 0L) throw MeshImportException("The file has no triangles.")
            writer.finish()
            MeshInfo(out, format, writer.triangles, writer.bounds(), writer.skipped)
        }
    }

    private fun looksLikeStl(head: ByteArray, len: Int) = len >= 84 || String(head, 0, len).startsWith("solid")

    /**
     * Many binary STLs also start with "solid" in their 80-byte header, so check
     * that the header is printable text and followed by a "facet" keyword.
     */
    private fun isAsciiStl(head: ByteArray, len: Int): Boolean {
        val text = String(head, 0, len, Charsets.ISO_8859_1)
        if (!text.trimStart().startsWith("solid")) return false
        val printable = head.take(len).all { it in 9..13 || it in 32..126 }
        return printable && (len < 84 || text.contains("facet") || text.contains("endsolid"))
    }

    private fun readBinaryStl(input: InputStream, writer: StlWriter) {
        if (readFully(input, ByteArray(80), 0, 80) < 80) throw MeshImportException("The STL file is truncated.")
        val countBytes = ByteArray(4)
        if (readFully(input, countBytes, 0, 4) < 4) throw MeshImportException("The STL file is truncated.")
        val count = ByteBuffer.wrap(countBytes).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
        val record = ByteArray(50)
        val buf = ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN)
        val v = FloatArray(9)
        var read = 0L
        while (read < count) {
            if (readFully(input, record, 0, 50) < 50) break // Tolerate a wrong count in the header.
            for (i in 0 until 9) v[i] = buf.getFloat(12 + 4 * i)
            writer.triangle(v)
            read++
        }
    }

    /** Reads up to [len] bytes, stopping early only at end of stream. (InputStream.readNBytes needs API 33.) */
    private fun readFully(input: InputStream, b: ByteArray, off: Int, len: Int): Int {
        var total = 0
        while (total < len) {
            val n = input.read(b, off + total, len - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private fun readAsciiStl(input: InputStream, writer: StlWriter) {
        val v = FloatArray(9)
        var n = 0
        LineReader(input).forEachLine { line ->
            val t = Tokens(line)
            if (t.next() == "vertex") {
                if (n < 9) {
                    v[n++] = t.nextFloat(); v[n++] = t.nextFloat(); v[n++] = t.nextFloat()
                }
            } else if (line.contains("endloop")) {
                if (n == 9) writer.triangle(v) else writer.skipped++
                n = 0
            }
        }
    }

    private fun readObj(input: InputStream, writer: StlWriter) {
        var verts = FloatArray(3 * 65536)
        var vertCount = 0
        val face = IntArray(64)
        val v = FloatArray(9)
        LineReader(input).forEachLine { line ->
            if (line.length < 2) return@forEachLine
            if (line[0] == 'v' && (line[1] == ' ' || line[1] == '\t')) {
                val t = Tokens(line); t.next()
                if (3 * (vertCount + 1) > verts.size) verts = verts.copyOf(verts.size * 2)
                // Only x y z are used; any extra values (colours, weights) are ignored.
                verts[3 * vertCount] = t.nextFloat()
                verts[3 * vertCount + 1] = t.nextFloat()
                verts[3 * vertCount + 2] = t.nextFloat()
                vertCount++
            } else if (line[0] == 'f' && (line[1] == ' ' || line[1] == '\t')) {
                val t = Tokens(line); t.next()
                var corners = 0
                while (true) {
                    val tok = t.next() ?: break
                    // "7", "7/2", "7//3" or "7/2/3"; negative indices count back from the end.
                    val slash = tok.indexOf('/')
                    val raw = (if (slash < 0) tok else tok.substring(0, slash)).toIntOrNull()
                    val idx = when {
                        raw == null || raw == 0 -> -1
                        raw > 0 -> raw - 1
                        else -> vertCount + raw
                    }
                    if (corners < face.size) face[corners++] = idx
                }
                // Triangulate polygons as a fan around the first corner.
                for (k in 1 until corners - 1) {
                    val a = face[0]; val b = face[k]; val c = face[k + 1]
                    if (a !in 0 until vertCount || b !in 0 until vertCount || c !in 0 until vertCount) {
                        writer.skipped++
                        continue
                    }
                    System.arraycopy(verts, 3 * a, v, 0, 3)
                    System.arraycopy(verts, 3 * b, v, 3, 3)
                    System.arraycopy(verts, 3 * c, v, 6, 3)
                    writer.triangle(v)
                }
            }
        }
    }

    /** Writes binary STL, tracking bounds; the triangle count is patched in at the end. */
    private class StlWriter(private val file: File) : AutoCloseable {
        private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
        private val record = ByteBuffer.allocate(50).order(ByteOrder.LITTLE_ENDIAN)
        var triangles = 0L
        var skipped = 0L
        private var minX = Float.MAX_VALUE; private var minY = Float.MAX_VALUE; private var minZ = Float.MAX_VALUE
        private var maxX = -Float.MAX_VALUE; private var maxY = -Float.MAX_VALUE; private var maxZ = -Float.MAX_VALUE
        private var finished = false

        init {
            val header = ByteArray(84)
            "MobileSlicer import".toByteArray().copyInto(header)
            out.write(header)
        }

        fun triangle(v: FloatArray) {
            for (i in 0 until 9) if (!v[i].isFinite()) { skipped++; return }
            record.clear()
            // Normal: let the engine work it out from the winding order.
            record.putFloat(0f).putFloat(0f).putFloat(0f)
            for (i in 0 until 9) record.putFloat(v[i])
            record.putShort(0)
            out.write(record.array(), 0, 50)
            for (i in 0 until 9 step 3) {
                val x = v[i]; val y = v[i + 1]; val z = v[i + 2]
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
                if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
            }
            triangles++
        }

        fun bounds() = Bounds(minX, minY, minZ, maxX, maxY, maxZ)

        fun finish() {
            out.flush()
            if (triangles > 0xffffffffL) throw MeshImportException("The mesh has too many triangles.")
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(80)
                val count = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(triangles.toInt()).array()
                raf.write(count)
            }
            finished = true
        }

        override fun close() {
            out.close()
            if (!finished) file.delete()
        }
    }

    /** Reads ASCII lines from a byte stream without decoding the whole file as text. */
    private class LineReader(private val input: InputStream) {
        private val buf = ByteArray(1 shl 16)
        private val line = StringBuilder(256)

        inline fun forEachLine(action: (String) -> Unit) {
            var n: Int
            while (input.read(buf).also { n = it } > 0) {
                for (i in 0 until n) {
                    val b = buf[i].toInt()
                    if (b == '\n'.code) {
                        action(line.toString()); line.setLength(0)
                    } else if (b != '\r'.code) {
                        line.append((b and 0xff).toChar())
                    }
                }
            }
            if (line.isNotEmpty()) action(line.toString())
        }
    }

    /** Whitespace tokenizer over one line. */
    private class Tokens(private val s: String) {
        private var i = 0
        fun next(): String? {
            while (i < s.length && s[i].isWhitespace()) i++
            if (i >= s.length) return null
            val start = i
            while (i < s.length && !s[i].isWhitespace()) i++
            return s.substring(start, i)
        }
        fun nextFloat(): Float = next()?.toFloatOrNull() ?: Float.NaN
    }

    private class CountingInputStream(private val inner: BufferedInputStream, private val progress: Progress) : InputStream() {
        private var count = 0L
        private var marked = 0L
        private var lastReport = 0L
        private fun advance(n: Int) {
            if (n <= 0) return
            count += n
            if (count - lastReport >= (1 shl 20)) { lastReport = count; progress.onBytes(count) }
        }
        override fun read(): Int = inner.read().also { if (it >= 0) advance(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { advance(it) }
        override fun markSupported() = true
        override fun mark(readlimit: Int) { inner.mark(readlimit); marked = count }
        override fun reset() { inner.reset(); count = marked }
        override fun close() = inner.close()
    }
}
