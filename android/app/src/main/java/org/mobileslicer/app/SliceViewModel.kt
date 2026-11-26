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
import org.mobileslicer.app.scene.BuildVolume
import org.mobileslicer.app.scene.History
import org.mobileslicer.app.scene.Ray
import org.mobileslicer.app.scene.Scene
import org.mobileslicer.app.scene.SceneObject
import org.mobileslicer.app.scene.arrange
import org.mobileslicer.app.scene.pickFaceNormal
import org.mobileslicer.app.scene.placeNew
import org.mobileslicer.app.scene.splitGroups
import org.mobileslicer.app.scene.splitInto
import org.mobileslicer.engine.MeshParts
import org.mobileslicer.engine.EngineException
import org.mobileslicer.engine.EngineRunner
import org.mobileslicer.engine.MeshImporter
import org.mobileslicer.engine.Profile
import org.mobileslicer.engine.SliceResult
import org.mobileslicer.engine.Transform
import java.io.File
import java.util.Locale

sealed interface SliceState {
    data object Idle : SliceState
    data class Running(val stagesDone: Int, val stageCount: Int, val nextStage: String?) : SliceState
    data class Done(val result: SliceResult) : SliceState
    data class Failed(val message: String, val log: String) : SliceState
}

data class UiState(
    val scene: Scene = Scene(),
    /** Set while a model is being read: file name, bytes read, total bytes if known. */
    val importing: Triple<String, Long, Long?>? = null,
    /** Text in the selected object's Scale field, in percent; kept in step with [heightMm]. */
    val scalePercent: String = "",
    /** Text in the selected object's Height field, in mm. */
    val heightMm: String = "",
    /** Waiting for a tap on the face to lay the selected object on. */
    val layFlatMode: Boolean = false,
    /** Something slow is happening (such as finding the tapped face). */
    val busy: String? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** Id of the selected profile. */
    val profileId: String = "",
    val slice: SliceState = SliceState.Idle,
    val message: String? = null,
)

class SliceViewModel(app: Application) : AndroidViewModel(app) {
    private val runner = EngineRunner(app)
    /** Bundled profiles, finest layers first. */
    val profiles: List<Profile> = EngineRunner.loadProfiles(app)

    private val _state = MutableStateFlow(
        // Start on the middle preset (Normal) when there are several.
        UiState(profileId = profiles.getOrNull(profiles.size / 2)?.id ?: "")
    )
    val state: StateFlow<UiState> = _state
    private val history = History<Scene>()
    private var sliceJob: Job? = null
    private var nextId = 1L
    private var moveInProgress = false

    private val workDir = File(app.cacheDir, "slice").apply {
        // Meshes from an earlier session aren't needed any more.
        deleteRecursively(); mkdirs()
    }

    fun profile(s: UiState = _state.value): Profile = profiles.firstOrNull { it.id == s.profileId } ?: profiles.first()

    fun volume(s: UiState = _state.value): BuildVolume = profile(s).let {
        BuildVolume(it.float("machine_width") ?: 235f, it.float("machine_depth") ?: 230f, it.float("machine_height") ?: 270f)
    }

    fun filamentDiameter(s: UiState = _state.value): Float = profile(s).float("material_diameter") ?: 1.75f

    /** Gap between arranged objects: room for both brims plus a little. */
    private fun spacing(s: UiState = _state.value): Float {
        val p = profile(s)
        val brim = if (p["adhesion_type"] == "brim") p.float("brim_width") ?: 0f else 0f
        return 2f * brim + 4f
    }

    fun selectProfile(id: String) = _state.update { it.copy(profileId = id, slice = SliceState.Idle) }

    /** Objects outside the build volume. */
    fun outOfBounds(s: UiState = _state.value): List<SceneObject> = volume(s).let { v -> s.scene.objects.filter { !it.fits(v) } }

    // ---------------------------------------------------------------- scene changes

    /** Applies [change] to the scene as one undoable step ([mergeKey] merges repeated edits). */
    private fun edit(mergeKey: String? = null, change: (Scene) -> Scene) {
        _state.update { s ->
            val next = change(s.scene)
            if (next == s.scene) return@update s
            history.record(s.scene, mergeKey)
            withFields(s.copy(scene = next, slice = SliceState.Idle, canUndo = history.canUndo, canRedo = history.canRedo))
        }
    }

    /** Refreshes the Scale and Height fields from the selected object. */
    private fun withFields(s: UiState, keepScaleText: Boolean = false, keepHeightText: Boolean = false): UiState {
        val o = s.scene.selected ?: return s.copy(scalePercent = "", heightMm = "", layFlatMode = false)
        return s.copy(
            scalePercent = if (keepScaleText) s.scalePercent else fmt(o.transform.scale * 100f, 2),
            heightMm = if (keepHeightText) s.heightMm else fmt(o.bounds.sizeZ, 1),
        )
    }

    fun undo() = _state.update { s ->
        val prev = history.undo(s.scene) ?: return@update s
        withFields(s.copy(scene = prev, slice = SliceState.Idle, layFlatMode = false, canUndo = history.canUndo, canRedo = history.canRedo))
    }

    fun redo() = _state.update { s ->
        val next = history.redo(s.scene) ?: return@update s
        withFields(s.copy(scene = next, slice = SliceState.Idle, layFlatMode = false, canUndo = history.canUndo, canRedo = history.canRedo))
    }

    fun select(id: Long?) = _state.update { s ->
        if (s.scene.selectedId == id) s
        else withFields(s.copy(scene = s.scene.copy(selectedId = id), layFlatMode = false))
    }

    /** Live move from a drag; the whole drag is one undo step. */
    fun moveBy(id: Long, dx: Float, dy: Float) {
        if (!moveInProgress) { history.breakMerge(); moveInProgress = true }
        edit("move-$id") { it.update(id) { o -> o.copy(x = o.x + dx, y = o.y + dy) } }
    }

    fun moveEnd() { moveInProgress = false; history.breakMerge() }

    fun setScalePercent(text: String) {
        val id = _state.value.scene.selectedId ?: return
        val factor = parse(text)?.div(100f)
        if (factor == null || factor <= 0f || factor > 1000f) { _state.update { it.copy(scalePercent = text) }; return }
        edit("scale-$id") { it.update(id) { o -> o.copy(transform = o.transform.copy(scale = factor)) } }
        _state.update { withFields(it.copy(scalePercent = text), keepScaleText = true) }
    }

    /** Sets the scale so the selected object is [text] millimetres tall. */
    fun setHeightMm(text: String) {
        val o = _state.value.scene.selected ?: return
        val target = parse(text)
        val height = o.bounds.sizeZ
        if (target == null || target <= 0f || height <= 0f) { _state.update { it.copy(heightMm = text) }; return }
        val factor = o.transform.scale * target / height
        if (factor > 1000f) { _state.update { it.copy(heightMm = text) }; return }
        edit("scale-${o.id}") { it.update(o.id) { x -> x.copy(transform = x.transform.copy(scale = factor)) } }
        _state.update { withFields(it.copy(heightMm = text), keepHeightText = true) }
    }

    /** Scales the selected object to fill at most 90% of the build volume in its tightest direction. */
    fun fitToBed() {
        val o = _state.value.scene.selected ?: return
        val v = volume()
        val b = o.localBounds
        val factor = minOf(v.width / b.sizeX, v.depth / b.sizeY, v.height / b.sizeZ) * 0.9f
        if (!factor.isFinite() || factor <= 0f) return
        history.breakMerge()
        edit { s -> s.update(o.id) { x -> x.copy(transform = x.transform.copy(scale = x.transform.scale * factor), x = v.width / 2f, y = v.depth / 2f) } }
    }

    /** Turns the selected object by [degrees] around an axis ("X", "Y" or "Z"). */
    fun rotate(axis: String, degrees: Float) {
        val id = _state.value.scene.selectedId ?: return
        val r = when (axis) {
            "X" -> Transform.rotationX(degrees)
            "Y" -> Transform.rotationY(degrees)
            else -> Transform.rotationZ(degrees)
        }
        history.breakMerge()
        edit { it.update(id) { o -> o.copy(transform = o.transform.thenRotate(r)) } }
    }

    fun resetRotation() {
        val id = _state.value.scene.selectedId ?: return
        history.breakMerge()
        edit { it.update(id) { o -> o.copy(transform = o.transform.copy(rotation = Transform.IDENTITY)) } }
    }

    fun setLayFlatMode(on: Boolean) = _state.update { it.copy(layFlatMode = on && it.scene.selected != null) }

    /** Lays object [id] on the face under [ray]. Reads the mesh to find the face, off the main thread. */
    fun layFlatAt(id: Long, ray: Ray) {
        val obj = _state.value.scene.objects.firstOrNull { it.id == id } ?: return
        _state.update { it.copy(busy = "Finding the face…", layFlatMode = false) }
        viewModelScope.launch {
            val normal = withContext(Dispatchers.Default) { pickFaceNormal(ray, obj) }
            _state.update { it.copy(busy = null) }
            if (normal == null) {
                _state.update { it.copy(message = "No face there. Tap Lay flat and try again.") }
                return@launch
            }
            // The face's direction after the object's current rotation, turned to point down.
            val n = FloatArray(3).also { Transform(obj.transform.rotation).apply(normal.x, normal.y, normal.z, it) }
            history.breakMerge()
            edit { s -> s.copy(selectedId = id).update(id) { o -> o.copy(transform = o.transform.thenRotate(Transform.pointingDown(n[0], n[1], n[2]))) } }
        }
    }

    fun duplicateSelected() {
        val o = _state.value.scene.selected ?: return
        val copy = o.copy(id = nextId++, name = o.name)
        history.breakMerge()
        edit { s -> Scene(placeNew(s.objects, copy, volume(), spacing()), copy.id) }
    }

    fun deleteSelected() {
        val id = _state.value.scene.selectedId ?: return
        history.breakMerge()
        edit { s -> Scene(s.objects.filter { it.id != id }, null) }
    }

    /**
     * Splits the selected object into one object per group of touching parts,
     * as one undoable step. Pieces stay where they were over the bed and drop
     * onto it.
     */
    fun splitSelected() {
        val o = _state.value.scene.selected ?: return
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = "Splitting…", layFlatMode = false) }
        viewModelScope.launch {
            val pieces = try {
                val groups = withContext(Dispatchers.Default) { o.splitGroups() }
                if (groups.size < 2) emptyList()
                else {
                    val ids = groups.map { nextId++ }
                    withContext(Dispatchers.IO) {
                        o.splitInto(groups.mapIndexed { i, g -> MeshParts.extract(o.mesh, g, File(workDir, "mesh-${ids[i]}.stl")) }, ids)
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(busy = null, message = "Couldn't split ${o.name}: ${e.message}") }
                return@launch
            }
            _state.update { it.copy(busy = null) }
            if (pieces.isEmpty()) {
                _state.update { it.copy(message = "All of ${o.name}'s parts touch each other, so it stays one object.") }
                return@launch
            }
            history.breakMerge()
            edit { s ->
                val at = s.objects.indexOfFirst { it.id == o.id }
                if (at < 0) return@edit s
                val objects = s.objects.subList(0, at) + pieces + s.objects.subList(at + 1, s.objects.size)
                Scene(objects, pieces.first().id)
            }
            val overlap = _state.value.scene.overlapping().any { (a, b) -> a in pieces || b in pieces }
            _state.update {
                it.copy(message = "Split into ${pieces.size} objects." + if (overlap) " Some overlap: tap Arrange to space them out." else "")
            }
        }
    }

    fun arrangeAll() {
        val s = _state.value
        val placed = arrange(s.scene.objects, volume(s), spacing(s))
        if (placed == null) {
            _state.update { it.copy(message = "These objects don't all fit on the bed. Scale some down or remove one.") }
            return
        }
        history.breakMerge()
        edit { it.copy(objects = placed) }
    }

    // ---------------------------------------------------------------- import

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
            _state.update { it.copy(importing = Triple(name, 0L, size), message = null) }
            try {
                val id = nextId++
                val info = withContext(Dispatchers.IO) {
                    val input = resolver.openInputStream(uri) ?: error("Couldn't open the file.")
                    input.use {
                        MeshImporter.import(
                            it, name, File(workDir, "mesh-$id.stl"),
                            progress = { read -> _state.update { s -> s.copy(importing = Triple(name, read, size)) } },
                            onAnalysing = { _state.update { s -> s.copy(busy = "Finding separate parts…") } },
                        )
                    }
                }
                _state.update { it.copy(importing = null, busy = null) }
                history.breakMerge()
                val obj = SceneObject(id, name, info, Transform(), 0f, 0f)
                edit { s -> Scene(placeNew(s.objects, obj, volume(), spacing()), id) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(importing = null, busy = null, message = "Couldn't read $name: ${e.message}") }
            }
        }
    }

    // ---------------------------------------------------------------- slicing

    fun slice() {
        val s = _state.value
        if (s.scene.objects.isEmpty() || s.slice is SliceState.Running || outOfBounds(s).isNotEmpty()) return
        val objects = s.scene.objects.map { it.toPlaced() }
        val stages = runner.stages
        _state.update { it.copy(slice = SliceState.Running(0, stages.size, stages.first()), message = null, layFlatMode = false) }
        sliceJob = viewModelScope.launch {
            try {
                val out = File(workDir, "output.gcode")
                val result = runner.slice(objects, profile(s), out) { done ->
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

    fun suggestedGcodeName(): String {
        val objs = _state.value.scene.objects
        val base = objs.firstOrNull()?.name?.substringBeforeLast('.') ?: "model"
        return (if (objs.size > 1) "$base-and-${objs.size - 1}-more" else base) + ".gcode"
    }

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

    private fun parse(text: String) = text.replace(',', '.').trim().toFloatOrNull()
    private fun fmt(v: Float, decimals: Int) = String.format(Locale.ROOT, "%.${decimals}f", v).trimEnd('0').trimEnd('.')
}
