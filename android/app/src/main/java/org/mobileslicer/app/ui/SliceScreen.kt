// SPDX-License-Identifier: AGPL-3.0-or-later
package org.mobileslicer.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.mobileslicer.app.SliceState
import org.mobileslicer.app.SliceViewModel
import org.mobileslicer.app.UiState
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SliceScreen(vm: SliceViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    val openModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importModel(uri)
    }
    val saveGcode = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x.gcode")) { uri ->
        if (uri != null) vm.saveGcode(uri)
    }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("MobileSlicer") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Many file pickers don't know STL/OBJ types, so accept anything and check the content.
                ModelCard(state, onOpen = { openModel.launch(arrayOf("*/*")) })
                if (state.model != null) {
                    PlacementCard(state, vm)
                    ProfileCard()
                    SliceCard(state, vm, onSave = { saveGcode.launch(vm.suggestedGcodeName()) })
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun ModelCard(state: UiState, onOpen: () -> Unit) = Section("Model") {
    val importing = state.importing
    val model = state.model
    when {
        importing != null -> {
            val (name, read, total) = importing
            Text("Reading $name…")
            if (total != null && total > 0) {
                LinearProgressIndicator(progress = { (read.toFloat() / total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        model != null -> {
            val b = model.info.bounds
            Text(model.name, style = MaterialTheme.typography.bodyLarge)
            Text("${model.info.sourceFormat} · ${"%,d".format(model.info.triangles)} triangles")
            Text("Size as exported: ${mm(b.sizeX)} × ${mm(b.sizeY)} × ${mm(b.sizeZ)} mm (X × Y × Z)")
            if (model.info.skippedTriangles > 0) {
                Text(
                    "${"%,d".format(model.info.skippedTriangles)} broken triangles were skipped.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedButton(onClick = onOpen) { Text("Open another model") }
        }
        else -> {
            Text("Open an STL or OBJ file, for example an export from Nomad Sculpt.")
            Button(onClick = onOpen) { Text("Open model") }
        }
    }
}

@Composable
private fun PlacementCard(state: UiState, vm: SliceViewModel) = Section("Placement") {
    val model = state.model ?: return@Section
    val scale = vm.scale(state)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = state.scalePercent,
            onValueChange = vm::setScalePercent,
            label = { Text("Scale %") },
            singleLine = true,
            isError = scale == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.width(140.dp),
        )
        OutlinedButton(onClick = vm::fitToBed) { Text("Fit to bed") }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Stand upright")
            Text(
                "Turn this on if the model lies on its back: sculpting apps often export with Y up.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(checked = state.upright, onCheckedChange = vm::setUpright)
    }
    if (scale == null) {
        Text("Enter a scale above 0%.", color = MaterialTheme.colorScheme.error)
        return@Section
    }
    val (w, d, h) = vm.placement(state).placedSize(model.info.bounds)
    Text("On the bed: ${mm(w)} × ${mm(d)} × ${mm(h)} mm (width × depth × height)")
    val v = vm.volume
    when {
        w > v.width || d > v.depth || h > v.height -> Text(
            "Too big for the ${mm(v.width)} × ${mm(v.depth)} × ${mm(v.height)} mm build volume. Scale it down or use Fit to bed.",
            color = MaterialTheme.colorScheme.error,
        )
        maxOf(w, d, h) < 5f -> Text(
            "Very small. The export may use different units; try Fit to bed.",
            color = MaterialTheme.colorScheme.tertiary,
        )
        else -> Text("Fits the build volume.")
    }
}

@Composable
private fun ProfileCard() = Section("Profile") {
    Text("Elegoo Neptune 4 Pro · PLA · 0.2 mm layers · 15% infill · no supports")
    Text(
        "Placeholder profile for testing. Real printer profiles and Elegoo's start G-code come in a later version.",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun SliceCard(state: UiState, vm: SliceViewModel, onSave: () -> Unit) = Section("Slice") {
    val model = state.model ?: return@Section
    val scale = vm.scale(state)
    val fits = scale != null && vm.placement(state).placedSize(model.info.bounds).let { (w, d, h) ->
        w <= vm.volume.width && d <= vm.volume.depth && h <= vm.volume.height
    }
    when (val s = state.slice) {
        is SliceState.Running -> {
            LinearProgressIndicator(progress = { s.stagesDone.toFloat() / s.stageCount }, Modifier.fillMaxWidth())
            Text("Step ${minOf(s.stagesDone + 1, s.stageCount)} of ${s.stageCount}: ${stageLabel(s.nextStage)}")
            OutlinedButton(onClick = vm::cancelSlice) { Text("Cancel") }
        }
        is SliceState.Done -> {
            val r = s.result
            Text("Sliced in ${"%.1f".format(r.sliceMillis / 1000.0)} s")
            r.layers?.let { Text("Layers: $it") }
            r.printTimeSeconds?.let { Text("Estimated print time: ${duration(it)}") }
            val meters = r.filamentMeters(vm.filamentDiameter)
            val grams = r.filamentGrams()
            if (meters != null && grams != null) {
                Text("Filament: ${"%.2f".format(meters)} m (about ${"%.0f".format(grams)} g of PLA)")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onSave) { Text("Save G-code") }
                OutlinedButton(onClick = vm::slice) { Text("Slice again") }
            }
        }
        is SliceState.Failed -> {
            Text(s.message, color = MaterialTheme.colorScheme.error)
            if (s.log.isNotBlank()) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    SelectionContainer {
                        Text(
                            s.log,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
            }
            Button(onClick = vm::slice, enabled = fits) { Text("Try again") }
        }
        SliceState.Idle -> Button(onClick = vm::slice, enabled = fits && state.importing == null) { Text("Slice") }
    }
}

private fun stageLabel(stage: String?) = when (stage) {
    "slice" -> "cutting the model into layers"
    "layerparts" -> "finding the parts of each layer"
    "inset+skin" -> "walls and top and bottom surfaces"
    "support" -> "supports"
    "export" -> "planning toolpaths"
    "process" -> "writing G-code"
    else -> "finishing"
}

private fun mm(v: Float) = String.format(Locale.ROOT, if (v < 10f) "%.2f" else "%.1f", v)

private fun duration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "$h h $m min" else "$m min ${seconds % 60} s"
}
