package io.github.homeboxnfc.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.homeboxnfc.HomeboxNfcApplication
import io.github.homeboxnfc.nfc.TagBus

@Composable
fun BatchScreen(app: HomeboxNfcApplication, vm: BatchViewModel = viewModel()) {
    DisposableEffect(vm) {
        TagBus.handler = vm::onTag
        onDispose { TagBus.handler = null }
    }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val session = ui.session

    Column(Modifier.padding(horizontal = 16.dp)) {
        ui.message?.let { MessageCard(it, ui.messageKind) }
        if (session == null) SetupView(app, vm) else RunView(app, session, vm)
    }
}

@Composable
private fun SetupView(app: HomeboxNfcApplication, vm: BatchViewModel) {
    val view by app.store.view.collectAsStateWithLifecycle()
    val locations = view?.index?.locationOptions.orEmpty()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var recursive by rememberSaveable { mutableStateOf(true) }
    var includeLocations by rememberSaveable { mutableStateOf(false) }
    val selected = locations.firstOrNull { it.node.id == selectedId }

    Column {
        ToggleRow("Include sub-locations", "Everything nested below the chosen location", recursive) { recursive = it }
        ToggleRow("Tag locations too", "Also queue sub-locations themselves, like boxes set up as locations", includeLocations) {
            includeLocations = it
        }
        Button(
            onClick = { selected?.let { vm.start(it, recursive, includeLocations) } },
            enabled = selected != null,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) { Text(selected?.let { "Start: ${it.node.name}" } ?: "Pick a location below") }
        SavedDataLine(app)
        HorizontalDivider()
        LazyColumn {
            items(locations, key = { it.node.id }) { opt ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { selectedId = opt.node.id }
                        .padding(start = (opt.depth * 20).dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selectedId == opt.node.id, onClick = { selectedId = opt.node.id })
                    Text(opt.node.name, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun RunView(app: HomeboxNfcApplication, session: BatchSession, vm: BatchViewModel) {
    val current = session.current
    val total = session.items.size
    var confirmEnd by remember { mutableStateOf(false) }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(session.rootName, style = MaterialTheme.typography.titleSmall)
        if (total > 0) {
            LinearProgressIndicator(
                progress = { session.index.coerceAtMost(total).toFloat() / total },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (current != null) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Tap a tag for", style = MaterialTheme.typography.labelLarge)
                    Text(current.name, style = MaterialTheme.typography.headlineMedium)
                    Text(
                        (if (current.isLocation) "Location in " else "In ") + current.path,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text("${session.index + 1} of $total", style = MaterialTheme.typography.labelMedium)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::back, enabled = session.index > 0, modifier = Modifier.weight(1f)) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text("Previous")
                }
                OutlinedButton(onClick = vm::skip, modifier = Modifier.weight(1f)) {
                    Text("Skip")
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(ButtonDefaults.IconSize))
                }
            }
            session.items.getOrNull(session.index + 1)?.let {
                Text("Up next: ${it.name}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("All done", style = MaterialTheme.typography.headlineSmall)
                    Text("Every entry in this run has been handled. Refresh to check for anything skipped or new.")
                }
            }
            if (session.index > 0) OutlinedButton(onClick = vm::back) { Text("Previous") }
        }

        HorizontalDivider()
        SavedDataLine(app)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = vm::refresh) { Text("Refresh list") }
            TextButton(
                onClick = { confirmEnd = true },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("End run") }
        }
    }

    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text("End this run?") },
            text = { Text("Tags already written stay written, and their Homebox updates still go through. Only your place in this list is lost.") },
            confirmButton = { TextButton(onClick = { confirmEnd = false; vm.endSession() }) { Text("End run") } },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Cancel") } },
        )
    }
}
