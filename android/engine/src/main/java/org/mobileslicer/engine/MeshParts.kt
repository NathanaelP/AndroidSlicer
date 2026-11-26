// SPDX-License-Identifier: AGPL-3.0-or-later
package org.mobileslicer.engine

import java.io.BufferedInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * One connected part of a mesh: a set of triangles joined by shared corners.
 * After import, each part's triangles sit together in the mesh file, from
 * [firstTriangle], largest part first.
 */
data class MeshPart(
    val firstTriangle: Int,
    val triangles: Int,
    /** Exact bounds of this part, in the mesh's own coordinates. */
    val bounds: Bounds,
    /** This part's sample points: points [sampleStart] until [sampleStart] + [sampleCount] of MeshInfo.samples. */
    val sampleStart: Int,
    val sampleCount: Int,
)

/** Result of [MeshParts.group]. */
class PartsResult(val parts: List<MeshPart>, val samples: FloatArray)

/**
 * Finds the connected parts of a mesh and rewrites it with each part's triangles
 * together. Corners are joined when their coordinates are exactly equal, which
 * is how exporters write shared vertices.
 */
object MeshParts {
    /** Spacing of extra points on large triangles: this many across the model's diagonal. */
    const val SURFACE_POINTS_PER_DIAGONAL = 100f
    private const val MAX_FILL_STEPS = 200

    private fun dist(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float): Float {
        val dx = ax - bx; val dy = ay - by; val dz = az - bz
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** Beyond this many parts the mesh is treated as one: it's a triangle soup, not separate objects. */
    const val MAX_PARTS = 10_000

    /**
     * Reads binary STL [src] (triangle count [triangles], bounds [b]) and writes
     * [dst] with triangles grouped by part. Returns the parts and per-part
     * sample points (see MeshInfo.samples).
     */
    fun group(src: File, dst: File, triangles: Int, b: Bounds): PartsResult {
        // Pass 1: weld corners and join each triangle's corners.
        val firstCorner = IntArray(triangles)
        var parent = IntArray(maxOf(1024, triangles / 2))
        var vertices = 0
        val ids = PositionMap(triangles)
        fun find(v: Int): Int {
            var r = v
            while (parent[r] != r) r = parent[r]
            var c = v
            while (parent[c] != r) { val n = parent[c]; parent[c] = r; c = n }  // path compression
            return r
        }
        fun union(a: Int, c: Int) {
            val ra = find(a); val rc = find(c)
            if (ra != rc) parent[maxOf(ra, rc)] = minOf(ra, rc)
        }
        forEachTriangle(src, triangles) { t, buf, o ->
            var first = -1
            for (k in 0 until 3) {
                val p = o + 12 + 12 * k
                val id = ids.findOrInsert(buf.getInt(p), buf.getInt(p + 4), buf.getInt(p + 8), vertices)
                if (id == vertices) {  // a new corner
                    if (vertices == parent.size) parent = parent.copyOf(parent.size * 2)
                    parent[vertices] = vertices
                    vertices++
                }
                if (first < 0) first = id else union(first, id)
            }
            firstCorner[t] = first
        }

        // Label parts and count their triangles.
        val partOfRoot = IntArray(vertices) { -1 }
        var partCount = 0
        val partOfTriangle = firstCorner  // reused in place
        for (t in 0 until triangles) {
            val r = find(firstCorner[t])
            if (partOfRoot[r] < 0) partOfRoot[r] = partCount++
            partOfTriangle[t] = partOfRoot[r]
        }
        if (partCount > MAX_PARTS) {
            // A triangle soup: keep the order and treat it as one part.
            src.copyTo(dst, overwrite = true)
            val samples = mapped(dst, triangles) { sample(it, b, 0, triangles, 0, LongSet()) }
            return PartsResult(listOf(MeshPart(0, triangles, b, 0, samples.size / 3)), samples)
        }
        if (partCount == 1) {
            // The usual case: one connected mesh. Nothing to reorder.
            if (!src.renameTo(dst)) src.copyTo(dst, overwrite = true)
            val samples = mapped(dst, triangles) { sample(it, b, 0, triangles, 0, LongSet()) }
            return PartsResult(listOf(MeshPart(0, triangles, b, 0, samples.size / 3)), samples)
        }
        val counts = IntArray(partCount)
        for (t in 0 until triangles) counts[partOfTriangle[t]]++
        // Largest part first.
        val order = (0 until partCount).sortedByDescending { counts[it] }
        val start = IntArray(partCount)
        var acc = 0
        for (p in order) { start[p] = acc; acc += counts[p] }
        val cursor = start.copyOf()

        // Pass 2: write each triangle to its part's slot, and measure each part.
        val lo = Array(partCount) { floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE) }
        val hi = Array(partCount) { floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE) }
        RandomAccessFile(dst, "rw").use { raf: RandomAccessFile ->
            raf.setLength(84L + 50L * triangles)
            val mapped = raf.channel.map(FileChannel.MapMode.READ_WRITE, 0, 84L + 50L * triangles)
            val map = mapped.order(ByteOrder.LITTLE_ENDIAN)
            val header = ByteArray(80)
            "MobileSlicer import, grouped by part".toByteArray().copyInto(header)
            map.put(header).putInt(triangles)
            forEachTriangle(src, triangles) { t, buf, o ->
                val p = partOfTriangle[t]
                map.position(84 + 50 * cursor[p]++)
                map.put(buf.array(), o, 50)
                val l = lo[p]; val h = hi[p]
                for (k in 0 until 3) {
                    val q = o + 12 + 12 * k
                    val x = buf.getFloat(q); val y = buf.getFloat(q + 4); val z = buf.getFloat(q + 8)
                    l[0] = min(l[0], x); l[1] = min(l[1], y); l[2] = min(l[2], z)
                    h[0] = max(h[0], x); h[1] = max(h[1], y); h[2] = max(h[2], z)
                }
            }
            mapped.force()
        }

        // Pass 3: sample each part on the same grid, part by part.
        val parts = ArrayList<MeshPart>(partCount)
        val chunks = ArrayList<FloatArray>()
        var points = 0
        val seen = LongSet()
        mapped(dst, triangles) { map ->
            for ((i, p) in order.withIndex()) {
                val s = sample(map, b, start[p], counts[p], i.toLong(), seen)
                chunks += s
                parts += MeshPart(start[p], counts[p], Bounds(lo[p][0], lo[p][1], lo[p][2], hi[p][0], hi[p][1], hi[p][2]), points, s.size / 3)
                points += s.size / 3
            }
        }
        val samples = FloatArray(points * 3)
        var off = 0
        for (c in chunks) { c.copyInto(samples, off); off += c.size }
        return PartsResult(parts, samples)
    }

    /**
     * Groups [mesh]'s parts into pieces whose surfaces come within [gap] (in the
     * mesh's own units) of each other, so pieces that touch, rest on or push into
     * each other stay together. Works on the sample points, so the real reach is
     * [gap] plus about one sample spacing. Each group lists part indices in order;
     * groups are ordered by their first part (largest first).
     */
    fun touchingGroups(mesh: MeshInfo, gap: Float): List<List<Int>> {
        val parts = mesh.parts
        if (parts.size < 2) return listOf(parts.indices.toList())
        val b = mesh.bounds
        val diag = sqrt(b.sizeX * b.sizeX + b.sizeY * b.sizeY + b.sizeZ * b.sizeZ)
        // Points are at most one fill spacing apart on a surface, so two surfaces
        // closer than [gap] have points within gap + spacing of each other.
        val reach = gap + maxOf(diag / SURFACE_POINTS_PER_DIAGONAL, 1e-6f)
        val s = mesh.samples
        val n = s.size / 3
        val partOf = IntArray(n)
        for ((i, p) in parts.withIndex()) for (k in p.sampleStart until p.sampleStart + p.sampleCount) partOf[k] = i

        // Bucket the points on a grid of [reach] cells: neighbours are in the 27 cells around.
        fun cell(v: Float, lo: Float) = ((v - lo) / reach).toInt()
        fun key(ix: Int, iy: Int, iz: Int) = (ix.toLong() shl 42) or (iy.toLong() shl 21) or iz.toLong()
        val keys = LongArray(n) { key(cell(s[3 * it], b.minX), cell(s[3 * it + 1], b.minY), cell(s[3 * it + 2], b.minZ)) }
        val order = (0 until n).sortedBy { keys[it] }.toIntArray()
        val sortedKeys = LongArray(n) { keys[order[it]] }

        val root = IntArray(parts.size) { it }
        fun find(v: Int): Int { var r = v; while (root[r] != r) r = root[r]; var c = v; while (root[c] != r) { val nx = root[c]; root[c] = r; c = nx }; return r }
        val r2 = reach * reach
        for (i in 0 until n) {
            val ix = cell(s[3 * i], b.minX); val iy = cell(s[3 * i + 1], b.minY); val iz = cell(s[3 * i + 2], b.minZ)
            for (ox in -1..1) for (oy in -1..1) for (oz in -1..1) {
                if (ix + ox < 0 || iy + oy < 0 || iz + oz < 0) continue
                val k = key(ix + ox, iy + oy, iz + oz)
                var at = java.util.Arrays.binarySearch(sortedKeys, k)
                if (at < 0) continue
                while (at > 0 && sortedKeys[at - 1] == k) at--
                while (at < n && sortedKeys[at] == k) {
                    val j = order[at++]
                    if (partOf[j] == partOf[i] || find(partOf[j]) == find(partOf[i])) continue
                    val dx = s[3 * i] - s[3 * j]; val dy = s[3 * i + 1] - s[3 * j + 1]; val dz = s[3 * i + 2] - s[3 * j + 2]
                    if (dx * dx + dy * dy + dz * dz <= r2) root[find(partOf[j])] = find(partOf[i])
                }
            }
        }
        return parts.indices.groupBy { find(it) }.values.sortedBy { it.first() }
    }

    /**
     * Writes the triangles of parts [indices] of [mesh] to [dst] as a mesh of its
     * own, with its parts, bounds and sample points carried over.
     */
    fun extract(mesh: MeshInfo, indices: List<Int>, dst: File): MeshInfo {
        val picked = indices.map { mesh.parts[it] }
        val triangles = picked.sumOf { it.triangles }
        val points = picked.sumOf { it.sampleCount }
        val samples = FloatArray(3 * points)
        val parts = ArrayList<MeshPart>(picked.size)
        var tri = 0
        var pt = 0
        RandomAccessFile(dst, "rw").use { raf: RandomAccessFile ->
            raf.setLength(0)
            val header = ByteArray(84)
            "MobileSlicer split".toByteArray().copyInto(header)
            ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putInt(80, triangles)
            raf.write(header)
            RandomAccessFile(mesh.stl, "r").use { src: RandomAccessFile ->
                val buf = ByteArray(50 * 8192)
                for (p in picked) {
                    src.seek(84L + 50L * p.firstTriangle)
                    var left = 50L * p.triangles
                    while (left > 0) {
                        val n = minOf(buf.size.toLong(), left).toInt()
                        src.readFully(buf, 0, n)
                        raf.write(buf, 0, n)
                        left -= n
                    }
                    mesh.samples.copyInto(samples, 3 * pt, 3 * p.sampleStart, 3 * (p.sampleStart + p.sampleCount))
                    parts += p.copy(firstTriangle = tri, sampleStart = pt)
                    tri += p.triangles
                    pt += p.sampleCount
                }
            }
        }
        val bounds = picked.map { it.bounds }.reduce { a, c ->
            Bounds(min(a.minX, c.minX), min(a.minY, c.minY), min(a.minZ, c.minZ), max(a.maxX, c.maxX), max(a.maxY, c.maxY), max(a.maxZ, c.maxZ))
        }
        return mesh.copy(stl = dst, triangles = triangles.toLong(), bounds = bounds, skippedTriangles = 0, samples = samples, parts = parts)
    }

    /** Calls [action] with each triangle's index and its 50-byte record (in [buf] at offset). */
    private inline fun forEachTriangle(stl: File, triangles: Int, action: (t: Int, buf: ByteBuffer, offset: Int) -> Unit) {
        val chunk = 8192
        val record = ByteArray(50 * chunk)
        val buf = ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN)
        BufferedInputStream(stl.inputStream(), 1 shl 16).use { input ->
            readFully(input, ByteArray(84))
            var t = 0
            while (t < triangles) {
                val n = minOf(chunk, triangles - t)
                if (readFully(input, record, 50 * n) < 50 * n) throw MeshImportException("The mesh file is truncated.")
                for (i in 0 until n) action(t + i, buf, 50 * i)
                t += n
            }
        }
    }

    private fun readFully(input: java.io.InputStream, b: ByteArray, len: Int = b.size): Int {
        var total = 0
        while (total < len) {
            val n = input.read(b, total, len - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private inline fun <T> mapped(stl: File, triangles: Int, use: (ByteBuffer) -> T): T =
        RandomAccessFile(stl, "r").use { raf ->
            use(raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, 84L + 50L * triangles).order(ByteOrder.LITTLE_ENDIAN))
        }

    /**
     * Points on the surface of triangles [first] until [first] + [count] of the
     * mapped mesh [map]: every vertex, plus points across large triangles, kept to
     * one per cell of a grid [MeshImporter.CELLS_PER_DIAGONAL] cells across the
     * whole mesh's diagonal. [tag] separates parts that share cells.
     */
    private fun sample(map: ByteBuffer, b: Bounds, first: Int, count: Int, tag: Long, seen: LongSet): FloatArray {
        val diag = sqrt(b.sizeX * b.sizeX + b.sizeY * b.sizeY + b.sizeZ * b.sizeZ)
        val cell = maxOf(diag / MeshImporter.CELLS_PER_DIAGONAL, 1e-6f)
        // Large triangles (flat faces of simple models) also get points across them,
        // this far apart, so contacts in the middle of a face aren't missed.
        val fill = maxOf(cell, diag / SURFACE_POINTS_PER_DIAGONAL)
        var out = FloatArray(3 * 64)
        var n = 0
        fun add(x: Float, y: Float, z: Float) {
            val ix = ((x - b.minX) / cell).toLong(); val iy = ((y - b.minY) / cell).toLong(); val iz = ((z - b.minZ) / cell).toLong()
            // Cells per axis stay under 1024 (the grid is 400 across the diagonal).
            if (seen.add((((tag shl 10) or ix) shl 10 or iy) shl 10 or iz)) {
                if (n + 3 > out.size) out = out.copyOf(out.size * 2)
                out[n] = x; out[n + 1] = y; out[n + 2] = z; n += 3
            }
        }
        for (t in first until first + count) {
            val o = 84 + 50 * t + 12
            val ax = map.getFloat(o); val ay = map.getFloat(o + 4); val az = map.getFloat(o + 8)
            val bx = map.getFloat(o + 12); val by = map.getFloat(o + 16); val bz = map.getFloat(o + 20)
            val cx = map.getFloat(o + 24); val cy = map.getFloat(o + 28); val cz = map.getFloat(o + 32)
            add(ax, ay, az); add(bx, by, bz); add(cx, cy, cz)
            val longest = maxOf(dist(ax, ay, az, bx, by, bz), dist(bx, by, bz, cx, cy, cz), dist(cx, cy, cz, ax, ay, az))
            if (longest > fill) {
                val steps = minOf(MAX_FILL_STEPS, kotlin.math.ceil(longest / fill).toInt())
                for (i in 0..steps) for (j in 0..steps - i) {
                    val u = i.toFloat() / steps; val v = j.toFloat() / steps
                    add(ax + (bx - ax) * u + (cx - ax) * v, ay + (by - ay) * u + (cy - ay) * v, az + (bz - az) * u + (cz - az) * v)
                }
            }
        }
        return out.copyOf(n)
    }
}

/** Maps a vertex position (three float bit patterns) to a small integer id. Open addressing, no boxing. */
internal class PositionMap(expectedTriangles: Int) {
    private var capacity = Integer.highestOneBit(maxOf(1024, expectedTriangles) - 1) shl 1
    private var xs = IntArray(capacity)
    private var ys = IntArray(capacity)
    private var zs = IntArray(capacity)
    private var vals = IntArray(capacity) { -1 }
    private var size = 0

    /** Returns the id stored for (x, y, z), or stores [newId] for it and returns that. */
    fun findOrInsert(x: Int, y: Int, z: Int, newId: Int): Int {
        if (size * 10 >= capacity * 6) grow()
        var i = slot(x, y, z, capacity)
        while (true) {
            val v = vals[i]
            if (v < 0) {
                xs[i] = x; ys[i] = y; zs[i] = z; vals[i] = newId; size++
                return newId
            }
            if (xs[i] == x && ys[i] == y && zs[i] == z) return v
            i = (i + 1) and (capacity - 1)
        }
    }

    private fun slot(x: Int, y: Int, z: Int, cap: Int): Int {
        var h = x.toLong() * -0x61c8864680b583ebL
        h = (h xor y.toLong()) * -0x61c8864680b583ebL
        h = (h xor z.toLong()) * -0x61c8864680b583ebL
        h = h xor (h ushr 31)
        return (h and (cap - 1).toLong()).toInt()
    }

    private fun grow() {
        val ox = xs; val oy = ys; val oz = zs; val ov = vals
        capacity *= 2
        xs = IntArray(capacity); ys = IntArray(capacity); zs = IntArray(capacity); vals = IntArray(capacity) { -1 }
        for (i in ov.indices) {
            if (ov[i] < 0) continue
            var j = slot(ox[i], oy[i], oz[i], capacity)
            while (vals[j] >= 0) j = (j + 1) and (capacity - 1)
            xs[j] = ox[i]; ys[j] = oy[i]; zs[j] = oz[i]; vals[j] = ov[i]
        }
    }
}

/** An open-addressing set of longs: no boxing, much faster than HashSet<Long> for millions of keys. */
internal class LongSet {
    private var keys = LongArray(1 shl 16)
    private var used = BooleanArray(1 shl 16)
    private var size = 0

    /** Adds [k]; returns true if it wasn't there yet. */
    fun add(k: Long): Boolean {
        if (size * 2 >= keys.size) grow()
        var i = slot(k, keys.size)
        while (used[i]) {
            if (keys[i] == k) return false
            i = (i + 1) and (keys.size - 1)
        }
        used[i] = true; keys[i] = k; size++
        return true
    }

    private fun slot(k: Long, n: Int): Int {
        var h = k * -0x61c8864680b583ebL  // Fibonacci hashing
        h = h xor (h ushr 29)
        return (h and (n - 1).toLong()).toInt()
    }

    private fun grow() {
        val oldK = keys; val oldU = used
        keys = LongArray(oldK.size * 2); used = BooleanArray(oldK.size * 2); size = 0
        for (i in oldK.indices) if (oldU[i]) add(oldK[i])
    }
}
