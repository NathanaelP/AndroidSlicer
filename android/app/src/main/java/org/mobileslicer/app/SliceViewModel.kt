// SPDX-License-Identifier: AGPL-3.0-or-later
package org.mobileslicer.app

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mobileslicer.engine.EngineException
import org.mobileslicer.engine.EngineRunner
import org.mobileslicer.engine.MeshImporter
import org.mobileslicer.engine.MeshInfo
import org.mobileslicer.engine.Placement
import org.mobileslicer.engine.Profile
import org.mobileslicer.engine.SliceResult
import java.io.File

data class LoadedModel(val name: String, val info: MeshInfo)

sealed interface SliceState {
    data object Idle : SliceState
    data class Running(val stagesDone: Int, val stageCount: Int, val nextStage: String?) : SliceState
    data class Done(val result: SliceResult) : SliceState
    data class Failed(val message: String, val log: String) : SliceState
}

data class UiState(
    val model: LoadedModel? = null,
    /** Set while a model is being read: file name, bytes read, total bytes if known. */
    val importing: Triple<String, Long, Long?>? = null,
    val scalePercent: String = "100",
    /** Height on the bed in mm, kept in step with the scale; either can be edited. */
    val heightMm: String = "",
    val upright: Boolean = false,
    /** Id of the selected profile. */
    val profileId: String = "",
    val slice: SliceState = SliceState.Idle,
    val message: String? = null,
)

/** Bed size in mm, read from the profile. */
data class BuildVolume(val width: Float, val depth: Float, val height: Float)

class SliceViewModel(app: Application) : AndroidViewModel(app) {
    private val runner = EngineRunner(app)
    /** Bundled profiles, finest layers first. */
    val profiles: List<Profile> = EngineRunner.loadProfiles(app)

    private val _state = MutableStateFlow(
        // Start on the middle preset (Normal) when there are several.
        UiState(profileId = profiles.getOrNull(profiles.size / 2)?.id ?: "")
    )

    fun profile(s: UiState = _state.value): Profile = profiles.firstOrNull { it.id == s.profileId } ?: profiles.first()

    fun volume(s: UiState = _state.value): BuildVolume = profile(s).let {
        BuildVolume(it.float("machine_width") ?: 235f, it.float("machine_depth") ?: 230f, it.float("machine_height") ?: 270f)
    }

    /** True when the placed model fits the build volume. */
    fun fits(s: UiState = _state.value): Boolean {
        val model = s.model ?: return false
        if (scale(s) == null) return false
        val (w, d, h) = placement(s).placedSize(model.info.bounds)
        val v = volume(s)
        return w <= v.width && d <= v.depth && h <= v.height
    }

    fun filamentDiameter(s: UiState = _state.value): Float = profile(s).float("material_diameter") ?: 1.75f

    fun selectProfile(id: String) = _state.update { it.copy(profileId = id, slice = SliceState.Idle) }

    val state: StateFlow<UiState> = _state
    private var sliceJob: Job? = null

    private val workDir get() = File(getApplication<Application>().cacheDir, "slice").apply { mkdirs() }

    /** Scale as a factor, or null if the text isn't a usable percentage. */
    fun scale(s: UiState = _state.value): Float? =
        s.scalePercent.replace(',', '.').toFloatOrNull()?.takeIf { it > 0f && it <= 100_000f }?.div(100f)

    fun placement(s: UiState = _state.value) = Placement(scale(s) ?: 1f, s.upright)

    fun importModel(uri: Uri) {
        if (_state.value.importing != null) return
        val resolver = getApplication<Application>().contentResolver
        viewModelScope.launch {
            val (name, size) = withContext(Dispatchers.IO) {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                    ?.use { c ->
                        if (c.moveToFirst()) {
                            val n = c.getString(0) ?: "model"
                            val sz = if (c.isNull(1)) null else c.getLong(1)
                            n to sz
                        } else null
                    } ?: ("model" to null)
            }
            sliceJob?.cancel()
            _state.update { it.copy(importing = Triple(name, 0L, size), slice = SliceState.Idle, message = null) }
            try {
                val info = withContext(Dispatchers.IO) {
                    val input = resolver.openInputStream(uri) ?: error("Couldn't open the file.")
                    input.use {
                        MeshImporter.import(it, name, File(workDir, "model.stl")) { read ->
                            _state.update { s -> s.copy(importing = Triple(name, read, size)) }
                        }
                    }
                }
                _state.update {
                    val s = it.copy(model = LoadedModel(name, info), importing = null, scalePercent = "100")
                    s.copy(heightMm = heightText(s))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(importing = null, message = "Couldn't read $name: ${e.message}") }
            }
        }
    }

    fun setScalePercent(text: String) = _state.update {
        val s = it.copy(scalePercent = text, slice = SliceState.Idle)
        if (scale(s) != null) s.copy(heightMm = heightText(s)) else s
    }

    /** Sets the scale so the model is [text] millimetres tall on the bed. */
    fun setHeightMm(text: String) = _state.update {
        val target = text.replace(',', '.').toFloatOrNull()
        val raw = unscaledHeight(it)
        val s = it.copy(heightMm = text, slice = SliceState.Idle)
        if (target != null && target > 0f && raw != null && raw > 0f) {
            s.copy(scalePercent = String.format(java.util.Locale.ROOT, "%.2f", target / raw * 100f))
        } else s
    }

    fun setUpright(upright: Boolean) = _state.update {
        val s = it.copy(upright = upright, slice = SliceState.Idle)
        s.copy(heightMm = heightText(s))
    }

    /** Model height in mm at 100% scale with the current orientation. */
    private fun unscaledHeight(s: UiState): Float? =
        s.model?.let { Placement(1f, s.upright).placedSize(it.info.bounds).third }

    private fun heightText(s: UiState): String {
        val h = unscaledHeight(s) ?: return ""
        val sc = scale(s) ?: return s.heightMm
        return String.format(java.util.Locale.ROOT, "%.1f", h * sc)
    }

    /** Scale so the model fills at most 90% of the build volume in its tightest direction. */
    fun fitToBed() {
        val model = _state.value.model ?: return
        val (w, d, h) = Placement(1f, _state.value.upright).placedSize(model.info.bounds)
        val volume = volume()
        val factor = minOf(volume.width / w, volume.depth / d, volume.height / h) * 0.9f
        if (!factor.isFinite() || factor <= 0f) return
        setScalePercent("%.1f".format(java.util.Locale.ROOT, factor * 100f))
    }

    fun slice() {
        val s = _state.value
        val model = s.model ?: return
        if (s.slice is SliceState.Running) return
        val placement = placement(s)
        val stages = runner.stages
        _state.update { it.copy(slice = SliceState.Running(0, stages.size, stages.first()), message = null) }
        sliceJob = viewModelScope.launch {
            try {
                val out = File(workDir, "output.gcode")
                val result = runner.slice(model.info.stl, profile(s), placement, out) { done ->
                    _state.update { it.copy(slice = SliceState.Running(done, stages.size, stages.getOrNull(done))) }
                }
                _state.update { it.copy(slice = SliceState.Done(result)) }
            } catch (e: CancellationException) {
                _state.update { it.copy(slice = SliceState.Idle) }
                throw e
            } catch (e: EngineException) {
                _state.update { it.copy(slice = SliceState.Failed(e.message ?: "Slicing failed.", e.logTail)) }
            } catch (e: Exception) {
                _state.update { it.copy(slice = SliceState.Failed("Slicing failed: ${e.message}", "")) }
            }
        }
    }

    fun cancelSlice() {
        sliceJob?.cancel()
    }

    fun suggestedGcodeName(): String =
        (_state.value.model?.name?.substringBeforeLast('.') ?: "model") + ".gcode"

    fun saveGcode(uri: Uri) {
        val result = (_state.value.slice as? SliceState.Done)?.result ?: return
        val resolver = getApplication<Application>().contentResolver
        viewModelScope.launch {
            val msg = try {
                withContext(Dispatchers.IO) {
                    resolver.openOutputStream(uri, "wt")?.use { out ->
                        result.gcode.inputStream().use { it.copyTo(out, 1 shl 16) }
                    } ?: error("Couldn't open the destination.")
                }
                "G-code saved."
            } catch (e: Exception) {
                "Couldn't save the G-code: ${e.message}"
            }
            _state.update { it.copy(message = msg) }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
}
