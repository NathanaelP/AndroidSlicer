// SPDX-License-Identifier: AGPL-3.0-or-later
package org.mobileslicer.app.scene

import org.mobileslicer.app.viewport.OrbitCamera
import org.mobileslicer.app.viewport.Vec3
import org.mobileslicer.engine.Bounds
import java.io.BufferedInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.tan

/** A ray in bed coordinates: origin plus a unit direction. */
data class Ray(val origin: Vec3, val dir: Vec3) {
    fun at(t: Float) = origin + dir * t
}

/** The ray from the camera through pixel ([px], [py]) of a [w] x [h] view. */
fun screenRay(camera: OrbitCamera, w: Float, h: Float, px: Float, py: Float): Ray {
    val eye = camera.eye()
    val f = (camera.target - eye).normalized()
    val s = f.cross(Vec3(0f, 0f, 1f)).normalized()
    val u = s.cross(f)
    val t = tan(camera.fovY / 2f)
    val nx = 2f * px / w - 1f
    val ny = 1f - 2f * py / h
    return Ray(eye, (f + s * (nx * t * (w / h)) + u * (ny * t)).normalized())
}

/** Distance along [r] to box [b], or null if it misses (slab method). */
fun rayBox(r: Ray, b: Bounds): Float? {
    var tMin = 0f; var tMax = Float.MAX_VALUE
    val o = floatArrayOf(r.origin.x, r.origin.y, r.origin.z)
    val d = floatArrayOf(r.dir.x, r.dir.y, r.dir.z)
    val lo = floatArrayOf(b.minX, b.minY, b.minZ)
    val hi = floatArrayOf(b.maxX, b.maxY, b.maxZ)
    for (i in 0 until 3) {
        if (abs(d[i]) < 1e-9f) {
            if (o[i] < lo[i] || o[i] > hi[i]) return null
        } else {
            var t1 = (lo[i] - o[i]) / d[i]; var t2 = (hi[i] - o[i]) / d[i]
            if (t1 > t2) { val tmp = t1; t1 = t2; t2 = tmp }
            tMin = maxOf(tMin, t1); tMax = minOf(tMax, t2)
            if (tMin > tMax) return null
        }
    }
    return tMin
}

/** Where [r] meets the horizontal plane at height [z], or null if it doesn't. */
fun rayPlaneZ(r: Ray, z: Float): Vec3? {
    if (abs(r.dir.z) < 1e-6f) return null
    val t = (z - r.origin.z) / r.dir.z
    return if (t > 0f) r.at(t) else null
}

/** The object whose bounding box [r] hits first, or null. */
fun pickObject(r: Ray, objects: List<SceneObject>): SceneObject? =
    objects.mapNotNull { o -> rayBox(r, o.bounds)?.let { o to it } }.minByOrNull { it.second }?.first

/**
 * The face of [obj] under [r]: tests every triangle of its mesh file, nearest
 * hit wins. Returns the face's outward normal in the mesh's own coordinates
 * (turned towards the viewer, since STL winding isn't always reliable), or null
 * if the ray misses the mesh.
 */
fun pickFaceNormal(r: Ray, obj: SceneObject, stl: File = obj.mesh.stl): Vec3? {
    // Into mesh coordinates: undo the move onto the bed, then scale and rotation.
    val m = obj.modelMatrix()
    val s = obj.transform.scale
    val rot = obj.transform.rotation
    fun toLocalDir(v: Vec3) = Vec3( // R^T v
        rot[0] * v.x + rot[3] * v.y + rot[6] * v.z,
        rot[1] * v.x + rot[4] * v.y + rot[7] * v.z,
        rot[2] * v.x + rot[5] * v.y + rot[8] * v.z,
    )
    val o = toLocalDir(r.origin - Vec3(m[12], m[13], m[14])) * (1f / s)
    val d = toLocalDir(r.dir).normalized()

    var best = Float.MAX_VALUE
    var bestN: Vec3? = null
    val chunk = 8192
    val record = ByteArray(50 * chunk)
    val buf = ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN)
    BufferedInputStream(stl.inputStream(), 1 shl 16).use { input ->
        var skip = 84
        while (skip > 0) { val n = input.read(ByteArray(skip)); if (n < 0) return null; skip -= n }
        while (true) {
            var read = 0
            while (read < record.size) { val n = input.read(record, read, record.size - read); if (n < 0) break; read += n }
            val tris = read / 50
            for (t in 0 until tris) {
                val p = 50 * t + 12
                val ax = buf.getFloat(p); val ay = buf.getFloat(p + 4); val az = buf.getFloat(p + 8)
                val e1x = buf.getFloat(p + 12) - ax; val e1y = buf.getFloat(p + 16) - ay; val e1z = buf.getFloat(p + 20) - az
                val e2x = buf.getFloat(p + 24) - ax; val e2y = buf.getFloat(p + 28) - ay; val e2z = buf.getFloat(p + 32) - az
                // Möller-Trumbore
                val px = d.y * e2z - d.z * e2y; val py = d.z * e2x - d.x * e2z; val pz = d.x * e2y - d.y * e2x
                val det = e1x * px + e1y * py + e1z * pz
                if (abs(det) < 1e-12f) continue
                val inv = 1f / det
                val tx = o.x - ax; val ty = o.y - ay; val tz = o.z - az
                val u = (tx * px + ty * py + tz * pz) * inv
                if (u < 0f || u > 1f) continue
                val qx = ty * e1z - tz * e1y; val qy = tz * e1x - tx * e1z; val qz = tx * e1y - ty * e1x
                val v = (d.x * qx + d.y * qy + d.z * qz) * inv
                if (v < 0f || u + v > 1f) continue
                val dist = (e2x * qx + e2y * qy + e2z * qz) * inv
                if (dist > 1e-6f && dist < best) {
                    best = dist
                    var n = Vec3(e1y * e2z - e1z * e2y, e1z * e2x - e1x * e2z, e1x * e2y - e1y * e2x).normalized()
                    if (n.dot(d) > 0f) n = n * -1f  // face the viewer
                    bestN = n
                }
            }
            if (read < record.size) break
        }
    }
    return bestN
}
