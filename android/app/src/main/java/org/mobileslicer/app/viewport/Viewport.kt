// SPDX-License-Identifier: AGPL-3.0-or-later
package org.mobileslicer.app.viewport

import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import org.mobileslicer.engine.MeshInfo
import org.mobileslicer.engine.Placement
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import kotlin.math.hypot

/** What the viewport shows. */
data class ViewportModel(val mesh: MeshInfo, val placement: Placement, val fits: Boolean)

/**
 * 3D view of the bed, build volume and model. One finger orbits, two fingers
 * pan and pinch to zoom, a double tap (or Fit view) frames the model.
 */
@Composable
fun Viewport(
    bed: Triple<Float, Float, Float>,
    model: ViewportModel?,
    modifier: Modifier = Modifier,
) {
    val (bedW, bedD, bedH) = bed
    var camera by remember { mutableStateOf(OrbitCamera.overview(bedW, bedD, bedH)) }
    var stats by remember { mutableStateOf<RenderStats?>(null) }
    var glView by remember { mutableStateOf<GLSurfaceView?>(null) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val renderer = remember { ViewportRenderer { s -> mainHandler.post { stats = s } } }

    fun frame(): OrbitCamera = model?.let {
        OrbitCamera.framing(bedW, bedD, it.placement.placedSize(it.mesh.bounds), camera.yaw, camera.pitch)
    } ?: OrbitCamera.overview(bedW, bedD, bedH)

    // Frame each newly imported model.
    LaunchedEffect(model?.mesh) {
        renderer.setMesh(model?.mesh?.stl, model?.mesh?.triangles ?: 0, System.identityHashCode(model?.mesh).toLong())
        camera = frame()
    }

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
                    bedWidth = bedW, bedDepth = bedD, bedHeight = bedH,
                    model = model?.let { modelMatrix(it.mesh.bounds, it.placement, bedW, bedD) },
                    modelColor = if (model?.fits == false) RED else AMBER,
                    background = BACKGROUND,
                )
                view.requestRender()
            },
            modifier = Modifier.fillMaxSize(),
        )
        // Gestures on a transparent layer above the GL view.
        Box(
            // Restart when the model changes, so a double tap frames the current one.
            Modifier.fillMaxSize().pointerInput(model?.mesh, model?.placement) {
                var lastTapUp = 0L
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val start = down.position
                    var moved = false
                    var maxPointers = 1
                    var prevCount = 0
                    var prevCentroid = Offset.Zero
                    var prevSpan = 0f
                    val h = size.height.toFloat().coerceAtLeast(1f)
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
                        if (pressed.size == prevCount) {
                            val delta = centroid - prevCentroid
                            if (!moved && (centroid - start).getDistance() > touchSlop) moved = true
                            if (pressed.size == 1) {
                                if (moved) camera = camera.orbit(delta.x, delta.y, h)
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
                    // A quick tap with no movement: two in a row reframe the model.
                    if (!moved && maxPointers == 1) {
                        val now = System.currentTimeMillis()
                        if (now - lastTapUp < 350) { camera = frame(); lastTapUp = 0 } else lastTapUp = now
                    }
                }
            },
        )
        FilledTonalButton(
            onClick = { camera = frame() },
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
        ) { Text("Fit view") }
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            val s = stats
            when {
                model == null -> Text("Open a model to see it here.", color = Color(0xFFB8BCC6), style = MaterialTheme.typography.bodyMedium)
                s?.error != null -> Text("Can't display this model: ${s.error}", color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall)
                s != null -> Text(
                    "%,d triangles".format(s.triangles) +
                        (s.framesPerSecond?.let { " · %.0f fps".format(it) } ?: "") +
                        (s.uploadMillis?.let { " · loaded in %.1f s".format(it / 1000.0) } ?: ""),
                    color = Color(0xFFB8BCC6),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private val AMBER = floatArrayOf(0.96f, 0.74f, 0.30f)
private val RED = floatArrayOf(0.90f, 0.36f, 0.30f)
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
