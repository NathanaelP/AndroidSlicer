// SPDX-License-Identifier: AGPL-3.0-or-later
package org.mobileslicer.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.mobileslicer.app.SliceViewModel
import org.mobileslicer.app.UiState
import org.mobileslicer.app.viewport.PreviewDraw
import org.mobileslicer.app.viewport.ViewportRenderer
import org.mobileslicer.engine.MoveType
import java.util.Locale
import kotlin.math.roundToInt

/** What the viewport should draw for the preview, or null to show the models. */
fun previewDraw(state: UiState): PreviewDraw? {
    val p = state.preview ?: return null
    if (!state.showPreview || p.layers.isEmpty()) return null
    val to = state.previewLayer.coerceIn(0, p.layers.lastIndex)
    return PreviewDraw(
        p, if (state.previewSingleLayer) to else 0, to,
        BooleanArray(MoveType.entries.size) { MoveType.entries[it] !in state.previewHidden },
        state.previewTravel,
    )
}

/** The layer slider and readout, over the bottom of the viewport. */
@Composable
fun BoxScope.LayerSlider(state: UiState, vm: SliceViewModel) {
    val p = state.preview ?: return
    if (!state.showPreview || p.layers.isEmpty()) return
    val i = state.previewLayer.coerceIn(0, p.layers.lastIndex)
    val layer = p.layers[i]
    val total = p.totalSeconds ?: p.layers.last().elapsedSeconds
    Column(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp)
            .background(Color(0xD91C1D22), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            "Layer ${i + 1} of ${p.layers.size} · Z %.2f mm · printed by %s of %s"
                .format(Locale.ROOT, layer.z, hm(layer.elapsedSeconds), hm(total)),
            color = Color(0xFFE6E8EE),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { vm.stepPreviewLayer(-1) }, enabled = i > 0) { Text("−") }
            Slider(
                value = i.toFloat(),
                onValueChange = { vm.setPreviewLayer(it.roundToInt()) },
                valueRange = 0f..maxOf(1, p.layers.lastIndex).toFloat(),
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(onClick = { vm.stepPreviewLayer(1) }, enabled = i < p.layers.lastIndex) { Text("+") }
        }
    }
}

/** Preview controls: models or toolpaths, which move types to show, and travel. */
@Composable
fun PreviewCard(state: UiState, vm: SliceViewModel) {
    val p = state.preview ?: return
    Section("Preview") {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = !state.showPreview, onClick = { vm.setShowPreview(false) },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
            ) { Text("Models") }
            SegmentedButton(
                selected = state.showPreview, onClick = { vm.setShowPreview(true) },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
            ) { Text("Toolpaths") }
        }
        if (!state.showPreview) return@Section
        val total = p.lengthByType.sum().coerceAtLeast(1e-6f)
        for (t in p.typesPresent) {
            val c = ViewportRenderer.PATH_COLORS
            CheckRow(
                checked = t !in state.previewHidden,
                onToggle = { vm.togglePreviewType(t) },
                swatch = Color(c[3 * t.ordinal], c[3 * t.ordinal + 1], c[3 * t.ordinal + 2]),
                label = t.label,
                detail = "%.0f%%".format(Locale.ROOT, 100f * p.lengthByType[t.ordinal] / total),
            )
        }
        val tc = ViewportRenderer.TRAVEL_COLOR
        CheckRow(state.previewTravel, { vm.setPreviewTravel(!state.previewTravel) }, Color(tc[0], tc[1], tc[2]), "Travel moves", "%,d".format(p.travelCount))
        CheckRow(state.previewSingleLayer, { vm.setPreviewSingleLayer(!state.previewSingleLayer) }, null, "Only the selected layer", "")
        Text(
            "Percentages are of the extruded path length. Lower layers are drawn a little darker.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun CheckRow(checked: Boolean, onToggle: () -> Unit, swatch: Color?, label: String, detail: String) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        if (swatch != null) Box(Modifier.size(16.dp).background(swatch, RoundedCornerShape(4.dp)))
        Text(label, Modifier.weight(1f))
        Text(detail, style = MaterialTheme.typography.bodySmall)
    }
}

/** Hours and minutes, such as "1 h 56 min" or "14 min". */
fun hm(seconds: Float): String {
    val m = (seconds / 60f).roundToInt()
    return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
}
