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

/**
 * One object on the bed: a mesh (binary STL), how it's turned and sized, and
 * where the centre of its footprint sits, in bed coordinates (mm). The engine
 * drops it onto the bed itself.
 */
data class PlacedObject(val mesh: File, val transform: Transform, val centerX: Float, val centerY: Float)

data class SliceResult(
    val gcode: File,
    val layers: Int?,
    val printTimeSeconds: Long?,
    val filamentMm3: Long?,
    val sliceMillis: Long,
    /** Toolpath extent and any unsafe nozzle temperatures found after slicing. */
    val check: GcodeCheck,
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
     * Slices [objects] together with [profile] and writes [output].
     * [onStage] is called with the number of stages finished, 0 to [stages].size.
     * Cancelling the calling coroutine kills the engine process.
     */
    suspend fun slice(
        objects: List<PlacedObject>,
        profile: Profile,
        output: File,
        onStage: (done: Int) -> Unit = {},
    ): SliceResult = withContext(Dispatchers.IO) {
        require(objects.isNotEmpty()) { "Nothing to slice" }
        val defs = definitionsDir()
        val libDir = context.applicationInfo.nativeLibraryDir
        val log = File(output.parentFile, output.nameWithoutExtension + ".log")
        output.delete()
        val cmd = buildCommand(File(libDir, "libcuraengine.so"), defs, profile, objects, output)

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
        val diameter = profile.float("material_diameter") ?: 1.75f
        val meters = filament?.let { it / (PI * (diameter / 2.0) * (diameter / 2.0)) / 1000.0 }
        // Anything more than 30 degrees below the print temperature mid-print is a mistake.
        val minSafe = (profile.float("material_print_temperature") ?: 200f) - 30.0
        val check = GcodePostProcessor.process(output, printTime, meters, minSafe)
        SliceResult(output, layers, printTime, filament, SystemClock.elapsedRealtime() - start, check)
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
        /**
         * The engine command line. Each object gets its own rotation and scale
         * (mesh_rotation_matrix is read when the mesh is loaded), then is centred
         * on the bed, dropped onto it, and moved to its spot with mesh_position_x/y,
         * which the engine adds after centring.
         */
        fun buildCommand(engine: File, defs: File, profile: Profile, objects: List<PlacedObject>, output: File): List<String> {
            val settings = profile.settings.flatMap { (k, v) -> listOf("-s", "$k=$v") }
            val bedCenterX = (profile.float("machine_width") ?: 0f) / 2f
            val bedCenterY = (profile.float("machine_depth") ?: 0f) / 2f
            return buildList {
                add(engine.path)
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
                for (o in objects) {
                    // Back to the extruder, so this object's rotation doesn't land on the previous object.
                    add("-e0")
                    add("-s"); add("mesh_rotation_matrix=${o.transform.matrix()}")
                    add("-l"); add(o.mesh.path)
                    // Settings after -l belong to this object.
                    add("-s"); add("center_object=True")
                    add("-s"); add("mesh_position_x=${o.centerX - bedCenterX}")
                    add("-s"); add("mesh_position_y=${o.centerY - bedCenterY}")
                    add("-s"); add("mesh_position_z=0")
                }
                add("-o"); add(output.path)
            }
        }

        private val PROGRESS = Regex("""Progress: (\S+) accomplished""")
        private val PRINT_TIME = Regex("""Print time \(s\): (\d+)""")
        private val FILAMENT = Regex("""Filament \(mm\^3\): (\d+)""")

        /** All bundled profiles, finest layers first. */
        fun loadProfiles(context: Context): List<Profile> =
            context.assets.list("profiles").orEmpty()
                .filter { it.endsWith(".json") }
                .map { name -> context.assets.open("profiles/$name").use { Profile.parseJson(it.reader().readText()) } }
                .sortedBy { it.float("layer_height") ?: 0f }
    }
}
