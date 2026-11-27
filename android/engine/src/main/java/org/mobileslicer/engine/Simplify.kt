// SPDX-License-Identifier: AGPL-3.0-or-later
package org.mobileslicer.engine

import java.io.File

/** The native simplifier, libmeshsimplify.so (engine/jni/meshsimplify.cpp, meshoptimizer). */
object NativeSimplifier {
    /** False when the library isn't there (desktop unit tests without a host build). */
    val available: Boolean by lazy {
        try {
            // Desktop tests point at the host build; the app loads it from the APK.
            val path = System.getProperty("mobileslicer.simplifier")
            if (path != null) System.load(path) else System.loadLibrary("meshsimplify")
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    /**
     * Welds and simplifies binary STL [input] into [output]: down to
     * [targetTriangles] (0 for as few as the error allows), moving the surface by
     * at most about [maxError] (mesh units). Open borders (hole rims) are kept as
     * they are. [stats] receives the error reached, the input triangles and the
     * welded vertices. Returns the triangles written, or a negative error code.
     */
    @JvmStatic
    external fun simplifyStl(input: String, output: String, targetTriangles: Long, maxError: Float, lockBorder: Boolean, stats: FloatArray): Long
}

/** A simplified mesh and how far, at most, its surface moved (mesh units). */
data class Simplified(val mesh: MeshInfo, val error: Float)

object MeshSimplifier {
    /**
     * Simplifies [mesh] into [out] (see NativeSimplifier.simplifyStl), then finds
     * its parts and checks it again, as on import.
     */
    fun simplify(mesh: MeshInfo, maxError: Float, out: File, targetTriangles: Long = 0): Simplified {
        val raw = File(out.parentFile, out.name + ".raw")
        try {
            val (triangles, error) = simplifyFile(mesh.stl, raw, maxError, targetTriangles)
            val b = MeshParts.boundsOf(raw, triangles)
            val grouped = MeshParts.group(raw, out, triangles, b)
            return Simplified(
                mesh.copy(
                    stl = out, triangles = triangles.toLong(), bounds = b, samples = grouped.samples, parts = grouped.parts,
                    // Errors add up at most; the original count is the file's.
                    simplifiedFrom = if (mesh.simplifiedFrom > 0) mesh.simplifiedFrom else mesh.triangles,
                    simplifyError = mesh.simplifyError + error,
                ),
                error,
            )
        } catch (e: Exception) {
            out.delete()
            throw e
        } finally {
            raw.delete()
        }
    }

    /** Simplifies one binary STL file into another; returns the triangles written and the error reached. */
    fun simplifyFile(input: File, output: File, maxError: Float, targetTriangles: Long = 0): Pair<Int, Float> {
        if (!NativeSimplifier.available) throw MeshImportException("The simplifier isn't available in this build.")
        val stats = FloatArray(3)
        val n = NativeSimplifier.simplifyStl(input.path, output.path, targetTriangles, maxError, true, stats)
        when {
            n == -2L -> throw MeshImportException("Not enough memory to simplify this mesh.")
            n < 0 -> throw MeshImportException("Couldn't simplify the mesh (error $n).")
            n == 0L -> throw MeshImportException("Simplifying left no triangles.")
        }
        return n.toInt() to stats[0]
    }
}
