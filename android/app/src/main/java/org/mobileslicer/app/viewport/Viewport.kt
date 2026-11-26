// SPDX-License-Identifier: AGPL-3.0-or-later
package org.mobileslicer.app.viewport

import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.mobileslicer.app.scene.BuildVolume
import org.mobileslicer.app.scene.Ray
import org.mobileslicer.app.scene.SceneObject
import org.mobileslicer.app.scene.pickObject
import org.mobileslicer.app.scene.rayBox
import org.mobileslicer.app.scene.rayPlaneZ
import org.mobileslicer.app.scene.screenRay
import org.mobileslicer.engine.Bounds
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import kotlin.math.hypot

/**
 * 3D view of the bed, build volume and objects.
 *
 * Touch: drag an object to select and move it; drag empty space to orbit; two
 * fingers pan and pinch to zoom; tap to select (or deselect on empty space);
 * double-tap empty space or Fit view to frame everything. In [layFlatMode] a
 * tap on an object reports the ray through [onFaceTap] instead.
 */
@Composable
fun Viewport(
    volume: BuildVolume,
    objects: List<SceneObject>,
    selectedId: Long?,
    /** Objects drawn in red, such as those outside the build volume. */
    problemIds: Set<Long>,
    layFlatMode: Boolean,
    onSelect: (Long?) -> Unit,
    onMove: (id: Long, dx: Float, dy: Float) -> Unit,
    onMoveEnd: () -> Unit,
    onFaceTap: (id: Long, ray: Ray) -> Unit,
    modifier: Modifier = Modifier,
) {
    var camera by remember { mutableStateOf(OrbitCamera.overview(volume.width, volume.depth, volume.height)) }
    var stats by remember { mutableStateOf<RenderStats?>(null) }
    var glView by remember { mutableStateOf<GLSurfaceView?>(null) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val renderer = remember { ViewportRenderer { s -> mainHandler.post { stats = s } } }

    // Gesture handlers run in a long-lived coroutine: read the latest values through these.
    val currentObjects by rememberUpdatedState(objects)
    val currentLayFlat by rememberUpdatedState(layFlatMode)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnMoveEnd by rememberUpdatedState(onMoveEnd)
    val currentOnFaceTap by rememberUpdatedState(onFaceTap)

    fun frameAll(): OrbitCamera {
        val objs = currentObjects
        if (objs.isEmpty()) return OrbitCamera.overview(volume.width, volume.depth, volume.height)
        val b = objs.map { it.bounds }.reduce { a, c ->
            Bounds(minOf(a.minX, c.minX), minOf(a.minY, c.minY), minOf(a.minZ, c.minZ), maxOf(a.maxX, c.maxX), maxOf(a.maxY, c.maxY), maxOf(a.maxZ, c.maxZ))
        }
        return OrbitCamera.framing(b, camera.yaw, camera.pitch)
    }

    // Keep the GPU's meshes in step with the scene, and frame the scene when objects are added or removed.
    val meshKeys = objects.map { it.mesh.stl.path }.distinct()
    LaunchedEffect(meshKeys) {
        renderer.setMeshes(objects.distinctBy { it.mesh.stl.path }.map { MeshRef(it.mesh.stl.path, it.mesh.stl, it.mesh.triangles, holeOutlines(it.mesh)) })
    }
    LaunchedEffect(objects.map { it.id }) { camera = frameAll() }

    // GLSurfaceView needs to know when the activity pauses and resumes.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, glView) {
        val view = glView
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> view?.onPause()
                Lifecycle.Event.ON_RESUME -> view?.onResume()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val touchSlop = LocalViewConfiguration.current.touchSlop
    Box(modifier) {
        AndroidView(
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    setEGLContextClientVersion(3)
                    setEGLConfigChooser(MultisampleConfigChooser())
                    preserveEGLContextOnPause = true
                    setRenderer(renderer)
                    renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                }.also { glView = it }
            },
            update = { view ->
                renderer.scene = SceneState(
                    camera = camera,
                    bedWidth = volume.width, bedDepth = volume.depth, bedHeight = volume.height,
                    items = objects.map { o ->
                        val problem = o.id in problemIds
                        val selected = o.id == selectedId
                        val base = when {
                            problem && selected -> RED_SELECTED
                            problem -> RED
                            selected -> SELECTED
                            else -> AMBER
                        }
                        // Floating parts in their own colour; parts are contiguous runs of triangles.
                        val floating = o.floating.map { it.part }.toSet()
                        val segments = if (floating.isEmpty()) listOf(Segment(0, o.mesh.triangles.toInt(), base))
                        else o.mesh.parts.mapIndexed { i, part ->
                            Segment(part.firstTriangle, part.triangles, if (i in floating) (if (selected) FLOATING_SELECTED else FLOATING) else base)
                        }
                        DrawItem(o.mesh.stl.path, o.modelMatrix(), segments, if (o.mesh.health.holes.isNotEmpty()) HOLE else null)
                    },
                    background = BACKGROUND,
                )
                view.requestRender()
            },
            modifier = Modifier.fillMaxSize(),
        )
        // Gestures on a transparent layer above the GL view.
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                var lastTapUp = 0L
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    val h = size.height.toFloat().coerceAtLeast(1f)
                    val start = down.position
                    val downRay = screenRay(camera, w, h, start.x, start.y)
                    val hit = pickObject(downRay, currentObjects)
                    // Dragging an object moves it in the horizontal plane through the grabbed point.
                    val grabZ = hit?.let { o -> rayBox(downRay, o.bounds)?.let { downRay.at(it).z } }
                    var lastPlanePoint = grabZ?.let { rayPlaneZ(downRay, it) }
                    var movingObject = false
                    var moved = false
                    var maxPointers = 1
                    var prevCount = 0
                    var prevCentroid = Offset.Zero
                    var prevSpan = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        maxPointers = maxOf(maxPointers, pressed.size)
                        val centroid = pressed.fold(Offset.Zero) { acc, c -> acc + c.position } / pressed.size.toFloat()
                        val span = if (pressed.size >= 2) hypot(
                            pressed[0].position.x - pressed[1].position.x,
                            pressed[0].position.y - pressed[1].position.y,
                        ) else 0f
                        if (!moved && (centroid - start).getDistance() > touchSlop) {
                            moved = true
                            // A one-finger drag that starts on an object moves it (not in lay-flat mode).
                            if (pressed.size == 1 && hit != null && !currentLayFlat && lastPlanePoint != null) {
                                movingObject = true
                                currentOnSelect(hit.id)
                            }
                        }
                        if (pressed.size == prevCount) {
                            val delta = centroid - prevCentroid
                            if (pressed.size == 1) {
                                if (movingObject && grabZ != null) {
                                    val p = rayPlaneZ(screenRay(camera, w, h, centroid.x, centroid.y), grabZ)
                                    val last = lastPlanePoint
                                    if (p != null && last != null) {
                                        currentOnMove(hit!!.id, p.x - last.x, p.y - last.y)
                                        lastPlanePoint = p
                                    }
                                } else if (moved) {
                                    camera = camera.orbit(delta.x, delta.y, h)
                                }
                            } else {
                                moved = true
                                camera = camera.pan(delta.x, delta.y, h)
                                if (prevSpan > 0f && span > 0f) camera = camera.zoom(span / prevSpan)
                            }
                        }
                        // When fingers are added or lifted, start measuring again to avoid jumps.
                        prevCount = pressed.size
                        prevCentroid = centroid
                        prevSpan = span
                        event.changes.forEach { it.consume() }
                    }
                    if (movingObject) currentOnMoveEnd()
                    if (!moved && maxPointers == 1) {
                        when {
                            hit != null && currentLayFlat -> currentOnFaceTap(hit.id, downRay)
                            hit != null -> currentOnSelect(hit.id)
                            else -> {
                                currentOnSelect(null)
                                // Two quick taps on empty space frame everything.
                                val now = System.currentTimeMillis()
                                if (now - lastTapUp < 350) { camera = frameAll(); lastTapUp = 0 } else lastTapUp = now
                            }
                        }
                    }
                }
            },
        )
        FilledTonalButton(
            onClick = { camera = frameAll() },
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
        ) { Text("Fit view") }
        if (layFlatMode) {
            Text(
                "Tap the face to put on the bed",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
                    .background(Color(0xCC2B5DA8), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            val s = stats
            when {
                objects.isEmpty() -> Text("Open a model to see it here.", color = Color(0xFFB8BCC6), style = MaterialTheme.typography.bodyMedium)
                s?.error != null -> Text("Can't display a model: ${s.error}", color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall)
                s != null -> Text(
                    "${objects.size} object${if (objects.size == 1) "" else "s"} · " +
                        "%,d triangles".format(s.triangles) +
                        (s.framesPerSecond?.let { " · %.0f fps".format(it) } ?: "") +
                        (s.uploadMillis?.let { " · last loaded in %.1f s".format(it / 1000.0) } ?: ""),
                    color = Color(0xFFB8BCC6),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private val AMBER = floatArrayOf(0.96f, 0.74f, 0.30f)
private val SELECTED = floatArrayOf(0.45f, 0.72f, 1.0f)
private val RED = floatArrayOf(0.90f, 0.36f, 0.30f)
private val RED_SELECTED = floatArrayOf(1.0f, 0.55f, 0.50f)
private val FLOATING = floatArrayOf(0.74f, 0.42f, 0.95f)
private val FLOATING_SELECTED = floatArrayOf(0.86f, 0.62f, 1.0f)
/** Hole rims: bright green, unlike any object colour. */
private val HOLE = floatArrayOf(0.30f, 1.0f, 0.45f)

/** Hole rims of [mesh] as line segments (x, y, z pairs), for the renderer. */
fun holeOutlines(mesh: org.mobileslicer.engine.MeshInfo): FloatArray {
    val holes = mesh.health.holes
    val out = FloatArray(holes.sumOf { 2 * it.size })
    var o = 0
    for (h in holes) {
        val n = h.size / 3
        for (i in 0 until n) {
            val a = 3 * i; val b = 3 * ((i + 1) % n)
            for (k in 0 until 3) out[o++] = h[a + k]
            for (k in 0 until 3) out[o++] = h[b + k]
        }
    }
    return out
}
private val BACKGROUND = floatArrayOf(0.11f, 0.11f, 0.13f, 1f)

/** Asks for an OpenGL ES 3 config with 4x multisampling, falling back to none. */
private class MultisampleConfigChooser : GLSurfaceView.EGLConfigChooser {
    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        fun pick(samples: Int): EGLConfig? {
            val attrs = intArrayOf(
                EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_DEPTH_SIZE, 24,
                EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                EGL10.EGL_SAMPLE_BUFFERS, if (samples > 0) 1 else 0,
                EGL10.EGL_SAMPLES, samples,
                EGL10.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            return if (egl.eglChooseConfig(display, attrs, configs, 1, count) && count[0] > 0) configs[0] else null
        }
        return pick(4) ?: pick(0) ?: throw IllegalStateException("No OpenGL ES 3 configuration available")
    }

    companion object { const val EGL_OPENGL_ES3_BIT = 0x40 }
}
