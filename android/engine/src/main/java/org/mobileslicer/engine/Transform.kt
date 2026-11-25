// SPDX-License-Identifier: AGPL-3.0-or-later
package org.mobileslicer.engine

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How a mesh is turned and sized before it goes on the bed: a rotation (3x3,
 * row-major, orthonormal) and a uniform scale, applied in the mesh's own
 * coordinates. CuraEngine receives the same thing as mesh_rotation_matrix.
 */
data class Transform(
    val rotation: List<Float> = IDENTITY,
    val scale: Float = 1f,
) {
    init { require(rotation.size == 9) }

    /** Applies scale and rotation to a point. */
    fun apply(x: Float, y: Float, z: Float, out: FloatArray, offset: Int = 0) {
        val r = rotation
        out[offset] = scale * (r[0] * x + r[1] * y + r[2] * z)
        out[offset + 1] = scale * (r[3] * x + r[4] * y + r[5] * z)
        out[offset + 2] = scale * (r[6] * x + r[7] * y + r[8] * z)
    }

    /** CuraEngine's mesh_rotation_matrix: rows of scale * rotation. */
    fun matrix(): String {
        val m = rotation.map { clean(it * scale) }
        return "[[${m[0]},${m[1]},${m[2]}],[${m[3]},${m[4]},${m[5]}],[${m[6]},${m[7]},${m[8]}]]"
    }

    /** This transform followed by rotation [r] (row-major), around the origin. */
    fun thenRotate(r: List<Float>): Transform = copy(rotation = orthonormalize(mul(r, rotation)))

    /** True if the rotation only swaps and flips axes, so bounding boxes stay exact. */
    fun isAxisAligned(): Boolean = rotation.all { abs(it) < 1e-6f || abs(abs(it) - 1f) < 1e-6f }

    companion object {
        val IDENTITY = listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

        /** Rotation by [degrees] around the X, Y or Z axis (right-handed). */
        fun rotationX(degrees: Float): List<Float> = rad(degrees).let { a ->
            val c = cleanTrig(cos(a)); val s = cleanTrig(sin(a))
            listOf(1f, 0f, 0f, 0f, c, -s, 0f, s, c)
        }
        fun rotationY(degrees: Float): List<Float> = rad(degrees).let { a ->
            val c = cleanTrig(cos(a)); val s = cleanTrig(sin(a))
            listOf(c, 0f, s, 0f, 1f, 0f, -s, 0f, c)
        }
        fun rotationZ(degrees: Float): List<Float> = rad(degrees).let { a ->
            val c = cleanTrig(cos(a)); val s = cleanTrig(sin(a))
            listOf(c, -s, 0f, s, c, 0f, 0f, 0f, 1f)
        }

        /**
         * The rotation that turns direction [n] to point straight down (0, 0, -1):
         * used to lay a model flat on the face with outward normal [n].
         */
        fun pointingDown(nx: Float, ny: Float, nz: Float): List<Float> {
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len == 0f) return IDENTITY
            val ax = nx / len; val ay = ny / len; val az = nz / len
            // Rotate a onto b = (0, 0, -1): axis a x b, angle acos(a . b) (Rodrigues).
            val c = -az
            if (c > 1f - 1e-6f) return IDENTITY
            if (c < -1f + 1e-6f) return rotationX(180f)
            val kx = -ay; val ky = ax; val kz = 0f      // a x b = (ay * -1 - 0, 0 - ax * -1, 0)
            val s = sqrt(kx * kx + ky * ky + kz * kz)
            val ux = kx / s; val uy = ky / s; val uz = kz / s
            val t = 1f - c
            return orthonormalize(listOf(
                t * ux * ux + c, t * ux * uy - s * uz, t * ux * uz + s * uy,
                t * ux * uy + s * uz, t * uy * uy + c, t * uy * uz - s * ux,
                t * ux * uz - s * uy, t * uy * uz + s * ux, t * uz * uz + c,
            ))
        }

        fun mul(a: List<Float>, b: List<Float>): List<Float> = List(9) { i ->
            val r = i / 3; val c = i % 3
            a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
        }

        /** Re-orthonormalizes a rotation so repeated turns don't drift. */
        fun orthonormalize(m: List<Float>): List<Float> {
            fun norm(v: FloatArray) = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).let { l -> FloatArray(3) { v[it] / l } }
            val x = norm(floatArrayOf(m[0], m[1], m[2]))
            var y = floatArrayOf(m[3], m[4], m[5])
            val d = x[0] * y[0] + x[1] * y[1] + x[2] * y[2]
            y = norm(FloatArray(3) { y[it] - d * x[it] })
            val z = floatArrayOf(x[1] * y[2] - x[2] * y[1], x[2] * y[0] - x[0] * y[2], x[0] * y[1] - x[1] * y[0])
            return listOf(x[0], x[1], x[2], y[0], y[1], y[2], z[0], z[1], z[2]).map { cleanTrig(it) }
        }

        private fun rad(deg: Float) = (deg * Math.PI / 180.0).toFloat()
        /** Snaps values within 1e-6 of 0 or ±1 so 90° turns stay exact. */
        private fun cleanTrig(v: Float) = when {
            abs(v) < 1e-6f -> 0f
            abs(v - 1f) < 1e-6f -> 1f
            abs(v + 1f) < 1e-6f -> -1f
            else -> v
        }
        private fun clean(v: Float): String = if (v == 0f) "0" else v.toString()
    }
}

/**
 * Bounds of a mesh after [t]. Exact for axis-aligned rotations (from the
 * import bounds). For other rotations it uses the mesh's sample points, which
 * can be up to one sample cell (about 0.25% of the model's size) inside the
 * true bounds.
 */
fun transformedBounds(mesh: MeshInfo, t: Transform): Bounds =
    transformedBounds(mesh.bounds, mesh.samples, 0, mesh.samples.size / 3, t)

/** Bounds of one [part] of [mesh] after [t]; exact or sampled as for the whole mesh. */
fun transformedBounds(mesh: MeshInfo, part: MeshPart, t: Transform): Bounds =
    transformedBounds(part.bounds, mesh.samples, part.sampleStart, part.sampleCount, t)

private fun transformedBounds(b: Bounds, samples: FloatArray, first: Int, count: Int, t: Transform): Bounds {
    val out = FloatArray(3)
    var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
    fun add(x: Float, y: Float, z: Float) {
        t.apply(x, y, z, out)
        minX = min(minX, out[0]); maxX = max(maxX, out[0])
        minY = min(minY, out[1]); maxY = max(maxY, out[1])
        minZ = min(minZ, out[2]); maxZ = max(maxZ, out[2])
    }
    if (t.isAxisAligned() || count == 0) {
        for (x in listOf(b.minX, b.maxX)) for (y in listOf(b.minY, b.maxY)) for (z in listOf(b.minZ, b.maxZ)) add(x, y, z)
    } else {
        var i = 3 * first
        val end = 3 * (first + count)
        while (i < end) { add(samples[i], samples[i + 1], samples[i + 2]); i += 3 }
    }
    return Bounds(minX, minY, minZ, maxX, maxY, maxZ)
}
