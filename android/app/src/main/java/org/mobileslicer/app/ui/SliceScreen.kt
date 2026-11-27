// SPDX-License-Identifier: AGPL-3.0-or-later
package org.mobileslicer.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.mobileslicer.app.SliceState
import org.mobileslicer.app.SliceViewModel
import org.mobileslicer.app.scene.unitGuesses
import org.mobileslicer.app.UiState
import org.mobileslicer.app.viewport.Viewport
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
    // Many file pickers don't know STL/OBJ types, so accept anything and check the content.
    val onOpen = { openModel.launch(arrayOf("*/*")) }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MobileSlicer") },
                actions = {
                    TextButton(onClick = vm::undo, enabled = state.canUndo) { Text("Undo") }
                    TextButton(onClick = vm::redo, enabled = state.canRedo) { Text("Redo") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val v = vm.volume(state)
            val problems = vm.outOfBounds(state).map { it.id }.toSet()
            val viewport: @Composable (Modifier) -> Unit = { mod ->
                Viewport(
                    volume = v,
                    objects = state.scene.objects,
                    selectedId = state.scene.selectedId,
                    problemIds = problems,
                    layFlatMode = state.layFlatMode,
                    onSelect = vm::select,
                    onMove = vm::moveBy,
                    onMoveEnd = vm::moveEnd,
                    onFaceTap = vm::layFlatAt,
                    modifier = mod,
                )
            }
            val panel: @Composable (Modifier) -> Unit = { mod ->
                Column(mod.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    ObjectsCard(state, vm, onOpen)
                    if (state.scene.selected != null) SelectedCard(state, vm)
                    if (state.scene.objects.isNotEmpty()) {
                        ProfileCard(state, vm)
                        SliceCard(state, vm, onSave = { saveGcode.launch(vm.suggestedGcodeName()) })
                    }
                }
            }
            if (maxWidth >= 840.dp) {
                // Tablet: viewport in the middle, settings panel on the right.
                Row(Modifier.fillMaxSize()) {
                    viewport(Modifier.weight(1f).fillMaxHeight())
                    panel(Modifier.width(440.dp).fillMaxHeight())
                }
            } else {
                // Phone: viewport on top, settings below.
                Column(Modifier.fillMaxSize()) {
                    viewport(Modifier.fillMaxWidth().weight(0.45f))
                    panel(Modifier.fillMaxWidth().weight(0.55f))
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ObjectsCard(state: UiState, vm: SliceViewModel, onOpen: () -> Unit) = Section("Objects") {
    val importing = state.importing
    if (importing != null) {
        val (name, read, total) = importing
        Text("Reading $name…")
        if (total != null && total > 0) {
            LinearProgressIndicator(progress = { (read.toFloat() / total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
    val objects = state.scene.objects
    if (objects.isEmpty() && importing == null) {
        Text("Open an STL or OBJ file, for example an export from Nomad Sculpt.")
    }
    val outOfBounds = vm.outOfBounds(state).map { it.id }.toSet()
    objects.forEach { o ->
        val selected = o.id == state.scene.selectedId
        Surface(
            color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().clickable { vm.select(if (selected) null else o.id) },
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(o.name, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                val b = o.bounds
                Text(
                    "${mm(b.sizeX)} × ${mm(b.sizeY)} × ${mm(b.sizeZ)} mm · %,d triangles".format(o.mesh.triangles) +
                        if (o.id in outOfBounds) " · outside the bed" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (o.id in outOfBounds) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (o.floating.isNotEmpty()) {
                    Text(floatingSummary(o.floating), style = MaterialTheme.typography.bodySmall, color = FLOATING_TEXT)
                }
                meshShortSummary(o.mesh.health)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onOpen, enabled = importing == null) { Text(if (objects.isEmpty()) "Open model" else "Add model") }
        if (objects.size > 1) OutlinedButton(onClick = vm::arrangeAll) { Text("Arrange") }
    }
    if (objects.isNotEmpty()) {
        Text("Drag an object in the view to move it. Tap one to select it.", style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectedCard(state: UiState, vm: SliceViewModel) = Section("Selected: ${state.scene.selected?.name ?: ""}") {
    val o = state.scene.selected ?: return@Section
    // Wraps on narrow screens, so Fit to bed moves to its own line instead of being squeezed.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = state.scalePercent,
            onValueChange = vm::setScalePercent,
            label = { Text("Scale %") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.width(130.dp),
        )
        OutlinedTextField(
            value = state.heightMm,
            onValueChange = vm::setHeightMm,
            label = { Text("Height mm") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.width(130.dp),
        )
        OutlinedButton(onClick = vm::fitToBed) { Text("Fit to bed") }
    }
    val b = o.bounds
    val v = vm.volume(state)
    Text("On the bed: ${mm(b.sizeX)} × ${mm(b.sizeY)} × ${mm(b.sizeZ)} mm (width × depth × height)")
    when {
        !o.fits(v) -> Text(
            if (b.sizeX > v.width || b.sizeY > v.depth || b.sizeZ > v.height)
                "Too big for the ${mm(v.width)} × ${mm(v.depth)} × ${mm(v.height)} mm build volume. Scale it down or use Fit to bed."
            else "Partly off the bed. Drag it back, or use Arrange.",
            color = MaterialTheme.colorScheme.error,
        )
        maxOf(b.sizeX, b.sizeY, b.sizeZ) < 5f -> Text(
            "Very small. The file may not be in millimetres: sculpting and CAD apps often export in other units. " +
                "Pick one below, enter the height you want, or use Fit to bed.",
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
    val guesses = o.unitGuesses(v)
    if (guesses.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            guesses.forEach { g ->
                FilledTonalButton(onClick = { vm.scaleUnits(g.factor) }) { Text("In ${g.unit} (${mm(g.heightMm)} mm tall)") }
            }
        }
    }

    if (o.floating.isNotEmpty()) {
        Text(
            floatingSummary(o.floating) + " (shown in purple), with nothing under " +
                (if (o.floating.size == 1) "it" else "them") + ". It will print in mid-air unless supports are added. " +
                "Supports come in a later version. Turning the model may also bring it down to the bed.",
            color = FLOATING_TEXT,
        )
    }

    MeshCheck(o, state, vm)
    SimplifyTools(o, state, vm)

    Text("Rotate", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = { vm.rotate("X", 90f) }) { Text("X 90°") }
        FilledTonalButton(onClick = { vm.rotate("Y", 90f) }) { Text("Y 90°") }
        FilledTonalButton(onClick = { vm.rotate("Z", 90f) }) { Text("Z 90°") }
        FilledTonalButton(onClick = { vm.rotate("Z", 45f) }) { Text("Z 45°") }
    }
    Text(
        "X 90° stands up a model that lies on its back: sculpting apps often export with Y up.",
        style = MaterialTheme.typography.bodySmall,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.layFlatMode) {
            Button(onClick = { vm.setLayFlatMode(false) }) { Text("Cancel lay flat") }
        } else {
            OutlinedButton(onClick = { vm.setLayFlatMode(true) }, enabled = state.busy == null) { Text("Lay flat…") }
        }
        OutlinedButton(onClick = vm::resetRotation) { Text("Reset rotation") }
    }
    state.busy?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (state.layFlatMode) Text("Tap the face of the model that should rest on the bed.", style = MaterialTheme.typography.bodySmall)

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = vm::duplicateSelected) { Text("Duplicate") }
        OutlinedButton(onClick = vm::deleteSelected) { Text("Delete") }
        if (o.mesh.parts.size > 1) {
            OutlinedButton(onClick = vm::splitSelected, enabled = state.busy == null) { Text("Split into parts") }
        }
    }
    if (o.mesh.parts.size > 1) {
        Text(
            "Split makes each separate piece its own object. Parts that touch stay together.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileCard(state: UiState, vm: SliceViewModel) = Section("Profile") {
    val p = vm.profile(state)
    Text("${p.machine} · ${p.material}")
    SingleChoiceSegmentedButtonRow {
        vm.profiles.forEachIndexed { i, option ->
            SegmentedButton(
                selected = option.id == p.id,
                onClick = { vm.selectProfile(option.id) },
                shape = SegmentedButtonDefaults.itemShape(i, vm.profiles.size),
                enabled = state.slice !is SliceState.Running,
            ) { Text(option.name) }
        }
    }
    val f = { key: String -> p.float(key) ?: 0f }
    Text(
        "Nozzle ${deg(f("material_print_temperature"))} °C (first layer ${deg(f("material_print_temperature_layer_0"))} °C) · " +
            "bed ${deg(f("material_bed_temperature"))} °C",
    )
    Text(
        "${p["wall_line_count"]} walls · ${p["top_layers"]} top and ${p["bottom_layers"]} bottom layers · " +
            "${deg(f("infill_sparse_density"))}% ${p["infill_pattern"]} infill · ${adhesion(p["adhesion_type"])} · " +
            if (p["support_enable"] == "True") "supports" else "no supports",
    )
    Text("Print speed ${deg(f("speed_print"))} mm/s · retraction ${p["retraction_amount"]} mm at ${deg(f("retraction_retract_speed"))} mm/s")
    Text(
        "From Cura 5.13's Elegoo Neptune 4 Pro profile, including Elegoo's start and end G-code.",
        style = MaterialTheme.typography.bodySmall,
    )
}

private fun deg(v: Float) = if (v == v.toInt().toFloat()) v.toInt().toString() else String.format(Locale.ROOT, "%.1f", v)

private fun adhesion(type: String?) = when (type) {
    "brim" -> "brim"
    "skirt" -> "skirt"
    "raft" -> "raft"
    "none" -> "no bed adhesion"
    else -> type ?: ""
}

@Composable
private fun SliceCard(state: UiState, vm: SliceViewModel, onSave: () -> Unit) = Section("Slice") {
    val objects = state.scene.objects
    val outside = vm.outOfBounds(state)
    val ready = objects.isNotEmpty() && outside.isEmpty() && state.importing == null
    Text(if (objects.size == 1) "1 object" else "${objects.size} objects, sliced together")
    if (outside.isNotEmpty()) {
        Text(
            "Can't slice yet: ${outside.joinToString { it.name }} ${if (outside.size == 1) "is" else "are"} outside the build volume.",
            color = MaterialTheme.colorScheme.error,
        )
    }
    val floating = objects.filter { it.floating.isNotEmpty() }
    if (floating.isNotEmpty()) {
        Text(
            "${floating.joinToString { it.name }} ${if (floating.size == 1) "has" else "have"} parts that would print in mid-air " +
                "(shown in purple). You can still slice, but those parts need supports to print properly.",
            color = FLOATING_TEXT,
        )
    }
    val overlapping = state.scene.overlapping()
    if (overlapping.isNotEmpty()) {
        Text(
            "Some objects overlap, so they'll print fused together. Use Arrange to space them out.",
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
    when (val s = state.slice) {
        is SliceState.Running -> {
            LinearProgressIndicator(progress = { s.stagesDone.toFloat() / s.stageCount }, Modifier.fillMaxWidth())
            Text("Step ${minOf(s.stagesDone + 1, s.stageCount)} of ${s.stageCount}: ${stageLabel(s.nextStage)}")
            OutlinedButton(onClick = vm::cancelSlice) { Text("Cancel") }
        }
        is SliceState.Done -> {
            val r = s.result
            val problems = r.check.temperatureProblems
            if (problems.isNotEmpty()) {
                val first = problems.first()
                Text(
                    "Don't print this. The G-code sets the nozzle to an unsafe temperature mid-print " +
                        "(${first.command} on layer ${first.layer}, line ${first.line}" +
                        (if (problems.size > 1) ", and ${problems.size - 1} more" else "") +
                        "). This is a bug in MobileSlicer, so saving is turned off. Please report it.",
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(onClick = vm::slice, enabled = ready) { Text("Slice again") }
                return@Section
            }
            Text("Sliced in ${"%.1f".format(r.sliceMillis / 1000.0)} s")
            r.layers?.let { Text("Layers: $it") }
            r.printTimeSeconds?.let { Text("Estimated print time: ${duration(it)}") }
            val profile = vm.profile(state)
            val meters = r.filamentMeters(vm.filamentDiameter(state))
            val grams = r.filamentGrams(profile.materialDensity)
            if (meters != null && grams != null) {
                Text("Filament: ${"%.2f".format(meters)} m (about ${"%.0f".format(grams)} g of ${profile.material})")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onSave) { Text("Save G-code") }
                OutlinedButton(onClick = vm::slice, enabled = ready) { Text("Slice again") }
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
            Button(onClick = vm::slice, enabled = ready) { Text("Try again") }
        }
        SliceState.Idle -> Button(onClick = vm::slice, enabled = ready) { Text("Slice") }
    }
}

/** What the edge check found, with Fill holes when there are holes to fill. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MeshCheck(o: org.mobileslicer.app.scene.SceneObject, state: UiState, vm: SliceViewModel) {
    val h = o.mesh.health
    val small = MaterialTheme.typography.bodySmall
    when {
        !h.checked -> Text("Mesh check skipped: too many triangles to check.", style = small)
        h.clean -> Text(
            "Mesh check: closed, no problems found." +
                if (h.degenerateTriangles > 0) " ${count(h.degenerateTriangles, "flat triangle")} with no area will be ignored." else "",
            style = small,
        )
        else -> {
            val lines = buildList {
                if (h.holes.isNotEmpty()) add(
                    "${count(h.holes.size, "hole")} (green outlines). The slicer will try to join the gaps layer by layer; " +
                        "Fill holes closes them properly."
                )
                if (h.untracedOpenEdges > 0) add(
                    "${count(h.untracedOpenEdges, "open edge")} that don't form a closed rim. The slicer will try to join them, " +
                        "but walls may have gaps there."
                )
                if (h.nonManifoldEdges > 0) add(
                    "${count(h.nonManifoldEdges, "edge")} shared by more than two faces (doubled or overlapping faces" +
                        (if (o.mesh.simplifiedFrom > 0) ", or pinched by simplifying" else "") + "). This usually slices fine."
                )
                if (h.flippedEdges > 0) add(
                    "${count(h.flippedEdges, "edge")} between faces pointing opposite ways (some faces are inside out). " +
                        "This usually slices fine."
                )
                if (h.degenerateTriangles > 0) add("${count(h.degenerateTriangles, "flat triangle")} with no area will be ignored.")
            }
            Text("Mesh check", style = MaterialTheme.typography.labelLarge)
            lines.forEach { Text(it, color = MaterialTheme.colorScheme.tertiary) }
            if (h.holes.isNotEmpty()) {
                OutlinedButton(onClick = vm::fillHolesSelected, enabled = state.busy == null) { Text("Fill holes") }
            }
        }
    }
}

/** Simplify, for dense meshes; and how much simplifying has already done. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SimplifyTools(o: org.mobileslicer.app.scene.SceneObject, state: UiState, vm: SliceViewModel) {
    val m = o.mesh
    if (m.simplifiedFrom > 0) {
        Text(
            "Simplified from %,d triangles; the surface moved by at most %s mm at this size."
                .format(m.simplifiedFrom, mm3(m.simplifyError * o.transform.scale)),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (m.triangles < SIMPLIFY_FROM) return
    Text("Simplify", style = MaterialTheme.typography.labelLarge)
    Text(
        "%,d triangles. Fewer make slicing and the view faster. The surface stays within the distance you pick; ".format(m.triangles) +
            "0.02 mm is far below what the printer can show.",
        style = MaterialTheme.typography.bodySmall,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (t in listOf(0.01f, 0.02f, 0.05f)) {
            OutlinedButton(onClick = { vm.simplifySelected(t) }, enabled = state.busy == null) { Text("Within ${mm3(t)} mm") }
        }
    }
}

/** Offered from this many triangles. */
private const val SIMPLIFY_FROM = 200_000L

private fun mm3(v: Float) = String.format(java.util.Locale.ROOT, "%.3f", v).trimEnd('0').trimEnd('.').ifEmpty { "0" }

private fun count(n: Int, noun: String) = "%,d %s".format(n, if (n == 1) noun else noun + "s")

/** One line for the objects list, or null when the mesh is fine. */
private fun meshShortSummary(h: org.mobileslicer.engine.PartHealth): String? = when {
    !h.checked || h.clean -> null
    h.holes.isNotEmpty() -> count(h.holes.size, "hole")
    h.openEdges > 0 -> count(h.openEdges, "open edge")
    else -> "Mesh has doubled or inside-out faces"
}

/** Purple, matching floating parts in the viewport; readable on light and dark cards. */
private val FLOATING_TEXT = androidx.compose.ui.graphics.Color(0xFF8E44C9)

private fun floatingSummary(f: List<org.mobileslicer.app.scene.FloatingPart>): String =
    if (f.size == 1) "1 separate part starts ${mm(f[0].height)} mm above the bed"
    else "${f.size} separate parts start ${mm(f.minOf { it.height })}–${mm(f.maxOf { it.height })} mm above the bed"

private fun stageLabel(stage: String?) = when (stage) {
    "slice" -> "cutting the models into layers"
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
