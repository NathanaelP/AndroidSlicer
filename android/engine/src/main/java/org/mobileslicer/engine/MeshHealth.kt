// SPDX-License-Identifier: AGPL-3.0-or-later
package org.mobileslicer.engine

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * What the edge check found in one part of a mesh. A clean, closed ("watertight")
 * part has every edge shared by exactly two triangles that run it in opposite
 * directions.
 */
data class PartHealth(
    /** Edges used by only one triangle: the rims of holes. */
    val openEdges: Int = 0,
    /** Edges shared by three or more triangles. */
    val nonManifoldEdges: Int = 0,
    /** Edges whose two triangles run it the same way: one of them faces inwards. */
    val flippedEdges: Int = 0,
    /** Triangles with two corners in the same place (no area). */
    val degenerateTriangles: Int = 0,
    /**
     * Each hole's rim as corner positions (x, y, z, ...), in the direction the
     * part's own triangles run along it. Triangles that fill the hole run the
     * other way.
     */
    val holes: List<FloatArray> = emptyList(),
    /** Open edges that don't close into a hole outline, or that weren't traced (too many). */
    val untracedOpenEdges: Int = 0,
    /** False when the mesh was too large to check. */
    val checked: Boolean = true,
) {
    val closed get() = checked && openEdges == 0
    val clean get() = closed && nonManifoldEdges == 0 && flippedEdges == 0

    operator fun plus(o: PartHealth) = PartHealth(
        openEdges + o.openEdges, nonManifoldEdges + o.nonManifoldEdges, flippedEdges + o.flippedEdges,
        degenerateTriangles + o.degenerateTriangles, holes + o.holes, untracedOpenEdges + o.untracedOpenEdges,
        checked && o.checked,
    )

    override fun equals(other: Any?) = other is PartHealth && openEdges == other.openEdges &&
        nonManifoldEdges == other.nonManifoldEdges && flippedEdges == other.flippedEdges &&
        degenerateTriangles == other.degenerateTriangles && untracedOpenEdges == other.untracedOpenEdges &&
        checked == other.checked && holes.size == other.holes.size && holes.indices.all { holes[it].contentEquals(other.holes[it]) }

    override fun hashCode() = openEdges * 31 + nonManifoldEdges * 17 + flippedEdges

    companion object {
        val UNCHECKED = PartHealth(checked = false)
    }
}

/**
 * The edge check: counts each edge's triangles from the welded corner ids that
 * [MeshParts.group] works out, and traces hole rims.
 */
internal object EdgeCheck {
    /** Larger meshes aren't checked: the edge list alone would need 24 bytes per triangle. */
    const val MAX_TRIANGLES = 8_000_000
    /** Beyond this many open edges the mesh is more of a surface than a solid: rims aren't traced. */
    const val MAX_TRACED_EDGES = 200_000
    /** Parts with fewer triangles than this aren't solids, so their rims aren't holes. */
    const val MIN_SOLID_TRIANGLES = 4

    /**
     * [corners]: three welded vertex ids per triangle of [src] (a binary STL).
     * [partOfTriangle] gives each triangle's part, [partCount] parts in all.
     * Returns the health of each part.
     */
    fun check(src: File, triangles: Int, corners: IntArray, partOfTriangle: IntArray, partCount: Int): Array<PartHealth> {
        val open = IntArray(partCount)
        val nonManifold = IntArray(partCount)
        val flipped = IntArray(partCount)
        val degenerate = IntArray(partCount)
        // One key per triangle side: the two corner ids (smaller first) and which way the triangle runs along it.
        val keys = LongArray(3 * triangles)
        var m = 0
        for (t in 0 until triangles) {
            val a = corners[3 * t]; val b = corners[3 * t + 1]; val c = corners[3 * t + 2]
            if (a == b || b == c || a == c) { degenerate[partOfTriangle[t]]++; continue }
            keys[m++] = key(a, b); keys[m++] = key(b, c); keys[m++] = key(c, a)
        }
        java.util.Arrays.sort(keys, 0, m)

        // Rim edges (from -> to, the way their triangle runs) per part.
        val rims = Array(partCount) { ArrayList<Long>() }
        var i = 0
        // First pass over runs: count, and remember open edges with a vertex we can map to a part.
        val openEdges = ArrayList<Long>()
        val nmVertex = ArrayList<Int>()
        val flVertex = ArrayList<Int>()
        while (i < m) {
            val k = keys[i] ushr 1
            var j = i
            var reversed = 0
            while (j < m && keys[j] ushr 1 == k) { reversed += (keys[j] and 1L).toInt(); j++ }
            val n = j - i
            val lo = (k ushr 30).toInt(); val hi = (k and ((1L shl 30) - 1)).toInt()
            when {
                n == 1 -> {
                    // The triangle runs lo -> hi unless the edge was stored reversed.
                    openEdges += if (reversed == 0) (lo.toLong() shl 32) or hi.toLong() else (hi.toLong() shl 32) or lo.toLong()
                }
                n == 2 && reversed != 1 -> flVertex += lo
                n > 2 -> nmVertex += lo
            }
            i = j
        }
        if (openEdges.isEmpty() && nmVertex.isEmpty() && flVertex.isEmpty()) {
            return Array(partCount) { PartHealth(degenerateTriangles = degenerate[it]) }
        }
        // Map the vertices we need to parts in one scan.
        val need = HashMap<Int, Int>()
        for (e in openEdges) need[(e ushr 32).toInt()] = -1
        for (v in nmVertex) need[v] = -1
        for (v in flVertex) need[v] = -1
        for (t in 0 until triangles) for (q in 0 until 3) {
            val v = corners[3 * t + q]
            val cur = need[v]
            if (cur != null && cur < 0) need[v] = partOfTriangle[t]
        }
        for (v in nmVertex) nonManifold[need.getValue(v)]++
        for (v in flVertex) flipped[need.getValue(v)]++
        for (e in openEdges) {
            val p = need.getValue((e ushr 32).toInt())
            open[p]++
            rims[p] += e
        }
        val rimCount = openEdges.size

        // Trace each part's rims into closed outlines.
        val holes = Array(partCount) { ArrayList<IntArray>() }
        val untraced = IntArray(partCount)
        val trianglesOfPart = IntArray(partCount)
        for (t in 0 until triangles) trianglesOfPart[partOfTriangle[t]]++
        for (p in 0 until partCount) {
            // A loose triangle or two isn't a solid with a hole: don't offer to fill it.
            if (rimCount > MAX_TRACED_EDGES || trianglesOfPart[p] < MIN_SOLID_TRIANGLES) { untraced[p] = open[p]; continue }
            val (loops, left) = trace(rims[p])
            holes[p].addAll(loops)
            untraced[p] = left
        }
        // Positions of the rim corners, read from the file.
        val pos = HashMap<Int, Int>()  // vertex id -> corner index (3 * triangle + k)
        for (list in holes) for (loop in list) for (v in loop) pos[v] = -1
        if (pos.isNotEmpty()) for (t in 0 until triangles) for (q in 0 until 3) {
            val v = corners[3 * t + q]
            if (pos[v] == -1) pos[v] = 3 * t + q
        }
        val outlines = Array(partCount) { ArrayList<FloatArray>() }
        if (pos.isNotEmpty()) RandomAccessFile(src, "r").use { raf ->
            val map = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, 84L + 50L * triangles).order(ByteOrder.LITTLE_ENDIAN)
            for (p in 0 until partCount) for (loop in holes[p]) {
                val xyz = FloatArray(3 * loop.size)
                for ((n, v) in loop.withIndex()) {
                    val corner = pos.getValue(v)
                    val o = 84 + 50 * (corner / 3) + 12 + 12 * (corner % 3)
                    xyz[3 * n] = map.getFloat(o); xyz[3 * n + 1] = map.getFloat(o + 4); xyz[3 * n + 2] = map.getFloat(o + 8)
                }
                outlines[p] += xyz
            }
        }
        return Array(partCount) {
            PartHealth(open[it], nonManifold[it], flipped[it], degenerate[it], outlines[it], untraced[it])
        }
    }

    private fun key(u: Int, v: Int): Long =
        if (u < v) ((u.toLong() shl 30) or v.toLong()) shl 1
        else (((v.toLong() shl 30) or u.toLong()) shl 1) or 1L

    /**
     * Follows rim edges (from -> to) into closed loops of corner ids. Where a corner
     * has several rim edges leaving it (two holes touching at a point), any one is
     * taken, which still gives closed loops. Returns the loops and how many edges
     * were left over in chains that don't close.
     */
    fun trace(edges: List<Long>): Pair<List<IntArray>, Int> {
        val out = HashMap<Int, ArrayDeque<Int>>()
        for (e in edges) out.getOrPut((e ushr 32).toInt()) { ArrayDeque() }.addLast(e.toInt())
        val loops = ArrayList<IntArray>()
        var left = 0
        for (e in edges) {
            val start = (e ushr 32).toInt()
            if (out[start].isNullOrEmpty()) continue
            val loop = ArrayList<Int>()
            loop += start
            var cur = start
            var closed = false
            while (true) {
                val next = out[cur]?.removeFirstOrNull() ?: break
                if (next == start) { closed = true; break }
                loop += next
                cur = next
            }
            if (closed && loop.size >= 3) loops += loop.toIntArray()
            else left += if (closed) loop.size else loop.size - 1
        }
        return loops to left
    }
}

/** Repairs that rewrite a mesh. */
object MeshRepair {
    /**
     * Closes every traced hole of [mesh] with a fan of triangles from the middle
     * of its rim, writes the result to [out] and checks it again. Works well for
     * the flat, roughly round openings exporters leave (an open base, a cut);
     * an odd-shaped hole is still closed, though not neatly.
     */
    fun fillHoles(mesh: MeshInfo, out: File): MeshInfo {
        val holes = mesh.parts.flatMap { it.health.holes }
        val added = holes.sumOf { if (it.size == 9) 1 else it.size / 3 }
        val total = mesh.triangles + added
        if (total > Int.MAX_VALUE / 50) throw MeshImportException("The mesh has too many triangles.")
        val raw = File(out.parentFile, out.name + ".raw")
        try {
            java.io.BufferedOutputStream(java.io.FileOutputStream(raw), 1 shl 16).use { os ->
                mesh.stl.inputStream().buffered(1 shl 16).use { input ->
                    val header = ByteArray(84)
                    var read = 0
                    while (read < 84) { val n = input.read(header, read, 84 - read); if (n < 0) throw MeshImportException("The mesh file is truncated."); read += n }
                    java.nio.ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putInt(80, total.toInt())
                    os.write(header)
                    val buf = ByteArray(1 shl 16)
                    var left = 50L * mesh.triangles
                    while (left > 0) {
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) throw MeshImportException("The mesh file is truncated.")
                        os.write(buf, 0, n)
                        left -= n
                    }
                }
                val rec = java.nio.ByteBuffer.allocate(50).order(ByteOrder.LITTLE_ENDIAN)
                fun tri(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, cx: Float, cy: Float, cz: Float) {
                    val ux = bx - ax; val uy = by - ay; val uz = bz - az
                    val vx = cx - ax; val vy = cy - ay; val vz = cz - az
                    var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
                    val len = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
                    if (len > 0f) { nx /= len; ny /= len; nz /= len }
                    rec.clear()
                    for (f in floatArrayOf(nx, ny, nz, ax, ay, az, bx, by, bz, cx, cy, cz)) rec.putFloat(f)
                    rec.putShort(0)
                    os.write(rec.array(), 0, 50)
                }
                for (h in holes) {
                    val n = h.size / 3
                    // The fill runs each rim edge the opposite way to the triangle beside it.
                    if (n == 3) { tri(h[6], h[7], h[8], h[3], h[4], h[5], h[0], h[1], h[2]); continue }
                    var cx = 0f; var cy = 0f; var cz = 0f
                    for (i in 0 until n) { cx += h[3 * i]; cy += h[3 * i + 1]; cz += h[3 * i + 2] }
                    cx /= n; cy /= n; cz /= n
                    for (i in 0 until n) {
                        val a = 3 * i; val b = 3 * ((i + 1) % n)
                        tri(h[b], h[b + 1], h[b + 2], h[a], h[a + 1], h[a + 2], cx, cy, cz)
                    }
                }
            }
            val grouped = MeshParts.group(raw, out, total.toInt(), mesh.bounds)
            return mesh.copy(stl = out, triangles = total, samples = grouped.samples, parts = grouped.parts)
        } catch (e: Exception) {
            out.delete()
            throw e
        } finally {
            raw.delete()
        }
    }
}
