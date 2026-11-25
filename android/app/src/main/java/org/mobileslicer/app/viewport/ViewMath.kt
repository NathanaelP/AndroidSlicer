// SPDX-License-Identifier: AGPL-3.0-or-later
package org.mobileslicer.app.viewport

import org.mobileslicer.engine.Bounds
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 4x4 matrices as column-major FloatArrays, the layout OpenGL expects.
 * Plain Kotlin (no android.opengl.Matrix) so it can be unit-tested on the desktop.
 */
object Mat4 {
    fun identity() = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }

    /** a × b */
    fun mul(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(16)
        for (c in 0 until 4) for (row in 0 until 4) {
            var s = 0f
            for (k in 0 until 4) s += a[k * 4 + row] * b[c * 4 + k]
            r[c * 4 + row] = s
        }
        return r
    }

    fun translation(x: Float, y: Float, z: Float) = identity().also { it[12] = x; it[13] = y; it[14] = z }

    fun perspective(fovYRadians: Float, aspect: Float, near: Float, far: Float): FloatArray {
        val f = 1f / tan(fovYRadians / 2f)
        return FloatArray(16).also {
            it[0] = f / aspect; it[5] = f
            it[10] = (far + near) / (near - far); it[11] = -1f
            it[14] = 2f * far * near / (near - far)
        }
    }

    fun lookAt(eye: Vec3, target: Vec3, up: Vec3): FloatArray {
        val f = (target - eye).normalized()
        val s = f.cross(up).normalized()
        val u = s.cross(f)
        return floatArrayOf(
            s.x, u.x, -f.x, 0f,
            s.y, u.y, -f.y, 0f,
            s.z, u.z, -f.z, 0f,
            -s.dot(eye), -u.dot(eye), f.dot(eye), 1f,
        )
    }

    /** Transforms a point (w = 1); returns x, y, z without dividing by w. */
    fun transform(m: FloatArray, p: Vec3) = Vec3(
        m[0] * p.x + m[4] * p.y + m[8] * p.z + m[12],
        m[1] * p.x + m[5] * p.y + m[9] * p.z + m[13],
        m[2] * p.x + m[6] * p.y + m[10] * p.z + m[14],
    )
}

data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Float) = Vec3(x * k, y * k, z * k)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun length() = sqrt(dot(this))
    fun normalized(): Vec3 = length().let { if (it == 0f) this else this * (1f / it) }
}

/** A camera that orbits a target point. Angles in radians; world units are millimetres, Z up. */
data class OrbitCamera(
    val target: Vec3,
    val distance: Float,
    /** Rotation around the vertical axis. 0 looks along +Y... see eye(). */
    val yaw: Float,
    /** Elevation above the bed plane. */
    val pitch: Float,
) {
    val fovY = (40.0 * PI / 180.0).toFloat()

    fun eye(): Vec3 = target + Vec3(cos(pitch) * cos(yaw), cos(pitch) * sin(yaw), sin(pitch)) * distance

    fun view(): FloatArray = Mat4.lookAt(eye(), target, Vec3(0f, 0f, 1f))

    fun projection(aspect: Float): FloatArray =
        Mat4.perspective(fovY, aspect, near = max(0.5f, distance * 0.01f), far = distance * 4f + 2000f)

    /** One-finger drag: [dx], [dy] in pixels on a view [viewHeight] pixels tall. */
    fun orbit(dx: Float, dy: Float, viewHeight: Float): OrbitCamera {
        val k = (PI / viewHeight).toFloat()  // dragging the full height turns half a circle
        return copy(yaw = yaw - dx * k, pitch = (pitch + dy * k).coerceIn(MIN_PITCH, MAX_PITCH)).aboveBed()
    }

    /**
     * Keeps the eye at least [MIN_EYE_HEIGHT] above the bed: the bed is opaque,
     * so a view from underneath shows nothing useful.
     */
    fun aboveBed(): OrbitCamera {
        if (eye().z >= MIN_EYE_HEIGHT) return this
        val minPitch = kotlin.math.asin(((MIN_EYE_HEIGHT - target.z) / distance).coerceIn(-1f, 1f))
        return copy(pitch = minPitch.coerceIn(MIN_PITCH, MAX_PITCH))
    }

    /** Two-finger drag: moves the target so the scene follows the fingers. */
    fun pan(dx: Float, dy: Float, viewHeight: Float): OrbitCamera {
        val worldPerPixel = 2f * distance * tan(fovY / 2f) / viewHeight
        val forward = (target - eye()).normalized()
        val right = forward.cross(Vec3(0f, 0f, 1f)).normalized()
        val up = right.cross(forward)
        val moved = target - right * (dx * worldPerPixel) + up * (dy * worldPerPixel)
        // The target never goes below the bed, so the eye can always stay above it.
        return copy(target = Vec3(moved.x, moved.y, maxOf(0f, moved.z))).aboveBed()
    }

    /** Pinch: [factor] > 1 zooms in. */
    fun zoom(factor: Float): OrbitCamera =
        if (factor <= 0f || !factor.isFinite()) this else copy(distance = (distance / factor).coerceIn(MIN_DISTANCE, MAX_DISTANCE)).aboveBed()

    companion object {
        const val MIN_PITCH = -0.35f   // a little below level, when the target is high enough
        const val MIN_EYE_HEIGHT = 1f  // mm above the bed
        const val MAX_PITCH = 1.55f    // nearly straight down
        const val MIN_DISTANCE = 5f
        const val MAX_DISTANCE = 3000f

        /** Looks at the whole build volume from the front, a little above. */
        fun overview(width: Float, depth: Float, height: Float): OrbitCamera {
            val target = Vec3(width / 2f, depth / 2f, height / 2f)
            val radius = sqrt(width * width + depth * depth + height * height) / 2f
            val fov = (40.0 * PI / 180.0).toFloat()
            // Far enough that the volume's bounding sphere fits the view's height.
            return OrbitCamera(target, radius / sin(fov / 2f), yaw = (-PI / 2).toFloat(), pitch = 0.5f)
        }

        /** Frames [b] (for example all objects on the bed), keeping the viewing angle. */
        fun framing(b: Bounds, yaw: Float, pitch: Float): OrbitCamera {
            val w = b.sizeX; val d = b.sizeY; val h = b.sizeZ
            val radius = max(5f, sqrt(w * w + d * d + h * h) / 2f)
            val fov = (40.0 * PI / 180.0).toFloat()
            val target = Vec3((b.minX + b.maxX) / 2f, (b.minY + b.maxY) / 2f, (b.minZ + b.maxZ) / 2f)
            return OrbitCamera(target, (radius / sin(fov / 2f) * 1.1f).coerceIn(MIN_DISTANCE, MAX_DISTANCE), yaw, pitch).aboveBed()
        }
    }
}
