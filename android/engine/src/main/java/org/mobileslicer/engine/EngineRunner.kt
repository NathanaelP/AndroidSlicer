// SPDX-License-Identifier: AGPL-3.0-or-later
package org.mobileslicer.engine

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.PI

/** How the mesh is placed before slicing. The engine centres it on the bed and drops it to the plate. */
data class Placement(
    /** Uniform scale, 1.0 = as imported (mesh units taken as millimetres). */
    val scale: Float = 1f,
    /** Rotate +90° about X, turning a Y-up model (common in sculpting apps) Z-up. */
    val upright: Boolean = false,
) {
    /** Row-major 3x3 matrix in CuraEngine's mesh_rotation_matrix format. */
    fun matrix(): String {
        val s = scale.toDouble()
        return if (upright) "[[$s,0,0],[0,0,${-s}],[0,$s,0]]" else "[[$s,0,0],[0,$s,0],[0,0,$s]]"
    }

    /** Size of [b] in millimetres once placed: (width, depth, height). */
    fun placedSize(b: Bounds): Triple<Float, Float, Float> =
        if (upright) Triple(b.sizeX * scale, b.sizeZ * scale, b.sizeY * scale)
        else Triple(b.sizeX * scale, b.sizeY * scale, b.sizeZ * scale)
}

data class SliceResult(
    val gcode: File,
    val layers: Int?,
    val printTimeSeconds: Long?,
    val filamentMm3: Long?,
    val sliceMillis: Long,
) {
    /** Filament length in metres for a given filament diameter. */
    fun filamentMeters(diameterMm: Float): Double? =
        filamentMm3?.let { it / (PI * (diameterMm / 2.0) * (diameterMm / 2.0)) / 1000.0 }

    /** Filament weight in grams for a given density (PLA ≈ 1.24 g/cm³). */
    fun filamentGrams(densityGPerCm3: Double = 1.24): Double? = filamentMm3?.let { it / 1000.0 * densityGPerCm3 }
}

class EngineException(message: String, val logTail: String) : IOException(message)

/**
 * Runs CuraEngine as a separate process. The engine ships in the APK as
 * libcuraengine.so, next to libtbb.so and libc++_shared.so, and is run from the
 * app's native library folder, the one place Android allows an app to run a
 * binary from.
 */
class EngineRunner(private val context: Context) {

    /** The engine's stages, in order, as named in its "Progress: <stage> accomplished" log lines. */
    val stages = listOf("slice", "layerparts", "inset+skin", "support", "export", "process")

    /**
     * Slices [mesh] (a binary STL) with [profile] and writes [output].
     * [onStage] is called with the number of stages finished, 0 to [stages].size.
     * Cancelling the calling coroutine kills the engine process.
     */
    suspend fun slice(
        mesh: File,
        profile: Profile,
        placement: Placement,
        output: File,
        onStage: (done: Int) -> Unit = {},
    ): SliceResult = withContext(Dispatchers.IO) {
        val defs = definitionsDir()
        val libDir = context.applicationInfo.nativeLibraryDir
        val log = File(output.parentFile, output.nameWithoutExtension + ".log")
        output.delete()

        val settings = profile.settings.flatMap { (k, v) -> listOf("-s", "$k=$v") }
        val cmd = buildList {
            add(File(libDir, "libcuraengine.so").path)
            add("slice")
            add("-d"); add(defs.path)
            // No Cura frontend resolves settings here, so load parent and formula-only
            // settings too (see engine/README.md).
            add("--force-read-parent"); add("--force-read-nondefault")
            add("-j"); add(File(defs, "fdmprinter.def.json").path)
            addAll(settings)
            // Apply the profile to extruder 0 as well; its own defaults would win otherwise.
            add("-e0")
            addAll(settings)
            add("-s"); add("mesh_rotation_matrix=${placement.matrix()}")
            add("-l"); add(mesh.path)
            add("-o"); add(output.path)
        }

        val pb = ProcessBuilder(cmd).directory(output.parentFile).redirectErrorStream(true)
        pb.environment()["LD_LIBRARY_PATH"] = libDir
        val start = SystemClock.elapsedRealtime()
        val process = pb.start()
        var stagesDone = 0
        var printTime: Long? = null
        var filament: Long? = null
        try {
            coroutineScope {
                // Reading the engine's output blocks and can't be interrupted, so on
                // cancellation kill the process; the read then ends.
                val killer = launch { try { awaitCancellation() } finally { process.destroyForcibly() } }
                log.bufferedWriter().use { w ->
                    process.inputStream.bufferedReader().forEachLine { line ->
                        w.write(line); w.newLine()
                        val stage = PROGRESS.find(line)?.groupValues?.get(1)
                        if (stage != null) {
                            stagesDone = (stages.indexOf(stage) + 1).coerceAtLeast(stagesDone)
                            onStage(stagesDone)
                        }
                        PRINT_TIME.find(line)?.let { printTime = it.groupValues[1].toLong() }
                        FILAMENT.find(line)?.let { filament = it.groupValues[1].toLong() }
                    }
                }
                val exit = process.waitFor()
                ensureActive()
                killer.cancel()
                if (exit != 0 || !output.exists()) {
                    throw EngineException("CuraEngine stopped with exit code $exit.", tail(log))
                }
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }

        val layers = output.bufferedReader().useLines { lines ->
            lines.take(50).firstOrNull { it.startsWith(";LAYER_COUNT:") }?.substringAfter(':')?.trim()?.toIntOrNull()
        }
        SliceResult(output, layers, printTime, filament, SystemClock.elapsedRealtime() - start)
    }

    /** Copies Cura's setting definitions out of the APK once per installed version. */
    private fun definitionsDir(): File {
        val dir = File(context.filesDir, "definitions")
        val stamp = File(dir, ".version")
        val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        if (stamp.exists() && stamp.readText() == version) return dir
        dir.deleteRecursively(); dir.mkdirs()
        for (name in context.assets.list("definitions").orEmpty()) {
            context.assets.open("definitions/$name").use { input ->
                File(dir, name).outputStream().use { input.copyTo(it) }
            }
        }
        stamp.writeText(version)
        return dir
    }

    private fun tail(log: File): String =
        if (log.exists()) log.readLines().takeLast(30).joinToString("\n") { it.take(300) } else ""

    companion object {
        private val PROGRESS = Regex("""Progress: (\S+) accomplished""")
        private val PRINT_TIME = Regex("""Print time \(s\): (\d+)""")
        private val FILAMENT = Regex("""Filament \(mm\^3\): (\d+)""")

        fun loadProfile(context: Context, asset: String): Profile =
            context.assets.open("profiles/$asset").use { Profile.parse(asset.substringBeforeLast('.'), it) }
    }
}
