// SPDX-License-Identifier: AGPL-3.0-or-later
//
// M0 engine test: run CuraEngine on the device as a separate process, slice the
// test cube with the same settings as the desktop reference run, and compare.
// Mirrors engine/test/slice-cube.sh; keep the two in step.
package org.mobileslicer.enginetest

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.security.MessageDigest
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var output: TextView
    private lateinit var sliceButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        sliceButton = Button(this).apply {
            text = "Slice test cube"
            setOnClickListener { runTest() }
        }
        val copyButton = Button(this).apply {
            text = "Copy results"
            setOnClickListener {
                val clipboard = getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("M0 results", output.text))
                Toast.makeText(this@MainActivity, "Results copied", Toast.LENGTH_SHORT).show()
            }
        }
        output = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            text = "Tap \"Slice test cube\" to run CuraEngine on this device."
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(sliceButton)
            addView(copyButton)
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(TextView(this@MainActivity).apply {
                text = "MobileSlicer M0: CuraEngine on Android"
                textSize = 20f
            })
            addView(buttons)
            addView(output, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        setContentView(ScrollView(this).apply { addView(column) })
        // Keep content clear of the status and navigation bars (edge-to-edge on Android 15+).
        column.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            v.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom)
            insets
        }
    }

    private fun runTest() {
        sliceButton.isEnabled = false
        output.text = "Slicing…"
        thread {
            val report = try {
                slice()
            } catch (e: Exception) {
                "ERROR: ${e.javaClass.simpleName}: ${e.message}\n\n${deviceInfo()}"
            }
            runOnUiThread {
                output.text = report
                sliceButton.isEnabled = true
            }
        }
    }

    private fun slice(): String {
        val work = File(filesDir, "m0").apply { deleteRecursively(); mkdirs() }
        for (name in assets.list("")!!.filter { it.contains('.') }) {
            assets.open(name).use { input -> File(work, name).outputStream().use { input.copyTo(it) } }
        }
        val libDir = applicationInfo.nativeLibraryDir
        val engine = File(libDir, "libcuraengine.so")
        val gcode = File(work, "cube20.gcode")
        val log = File(work, "engine.log")

        val settings = File(work, "m0-settings.txt").readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .flatMap { listOf("-s", it) }
        val cmd = mutableListOf(
            engine.path, "slice", "-d", work.path,
            "--force-read-parent", "--force-read-nondefault",
            "-j", File(work, "fdmprinter.def.json").path,
        )
        cmd += settings
        cmd += "-e0"
        cmd += settings
        cmd += listOf("-l", File(work, "cube20.stl").path, "-o", gcode.path)

        val pb = ProcessBuilder(cmd)
            .directory(work)
            .redirectErrorStream(true)
            .redirectOutput(log)
        pb.environment()["LD_LIBRARY_PATH"] = libDir
        val start = SystemClock.elapsedRealtime()
        val exit = pb.start().waitFor()
        val seconds = (SystemClock.elapsedRealtime() - start) / 1000.0

        val logText = log.readText()
        val result = linkedMapOf(
            "layers" to (gcode.takeIf { it.exists() }?.useLines { lines ->
                lines.firstOrNull { it.startsWith(";LAYER_COUNT:") }?.substringAfter(':')
            } ?: "missing"),
            "print_time_s" to (Regex("""Print time \(s\): (\d+)""").find(logText)?.groupValues?.get(1) ?: "missing"),
            "filament_mm3" to (Regex("""Filament \(mm\^3\): (\d+)""").find(logText)?.groupValues?.get(1) ?: "missing"),
            "toolpath_sha256" to (if (gcode.exists()) toolpathHash(gcode) else "missing"),
        )
        val expected = File(work, "m0-expected.txt").readLines()
            .filter { it.contains('=') && !it.startsWith("#") }
            .associate { it.substringBefore('=') to it.substringAfter('=') }

        val sb = StringBuilder()
        val pass = exit == 0 && result.all { (k, v) -> expected[k] == v }
        sb.appendLine(if (pass) "RESULT: PASS (matches desktop exactly)" else "RESULT: DIFFERENT (see below)")
        sb.appendLine()
        sb.appendLine("engine exit code: $exit")
        sb.appendLine("slice took: %.2f s".format(seconds))
        for ((k, v) in result) {
            val mark = if (expected[k] == v) "same" else "desktop ${expected[k]}"
            sb.appendLine("$k: $v  ($mark)")
        }
        sb.appendLine()
        sb.append(deviceInfo())
        if (exit != 0 || result.containsValue("missing")) {
            sb.appendLine()
            sb.appendLine("--- end of engine log ---")
            logText.lines().takeLast(40).forEach { sb.appendLine(it.take(300)) }
        }
        return sb.toString()
    }

    /** SHA-256 of the G-code with comment lines removed; same as `grep -v '^;' | sha256sum`. */
    private fun toolpathHash(gcode: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        gcode.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (!line.startsWith(";")) md.update((line + "\n").toByteArray())
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun deviceInfo(): String = buildString {
        appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("abi: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("app: ${packageManager.getPackageInfo(packageName, 0).versionName}")
    }
}
