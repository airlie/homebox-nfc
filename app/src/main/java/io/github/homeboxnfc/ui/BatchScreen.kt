package io.github.homeboxnfc.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.homeboxnfc.nfc.TagBus

@Composable
fun BatchScreen(vm: BatchViewModel = viewModel()) {
    DisposableEffect(vm) {
        TagBus.handler = vm::onTag
        onDispose { TagBus.handler = null }
    }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val session = ui.session

    Column(Modifier.padding(horizontal = 16.dp)) {
        ui.message?.let { MessageCard(it, ui.messageKind) }
        if (session == null) {
            SetupView(ui, vm)
        } else {
            RunView(ui, session, vm)
        }
    }
}

@Composable
internal fun MessageCard(text: String, kind: MessageKind) {
    val scheme = MaterialTheme.colorScheme
    val (bg, fg) = when (kind) {
        MessageKind.INFO -> scheme.surfaceVariant to scheme.onSurfaceVariant
        MessageKind.SUCCESS -> scheme.primaryContainer to scheme.onPrimaryContainer
        MessageKind.WARNING -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        MessageKind.ERROR -> scheme.errorContainer to scheme.onErrorContainer
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = bg, contentColor = fg),
    ) {
        Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SetupView(ui: BatchUi, vm: BatchViewModel) {
    var selected by remember { mutableStateOf<LocationOption?>(null) }
    var recursive by remember { mutableStateOf(true) }
    var includeLocations by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { if (ui.locations.isEmpty()) vm.loadLocations() }

    Column {
        ToggleRow("Include sub-locations", "Everything nested below the chosen location", recursive) { recursive = it }
        ToggleRow("Tag locations too", "Also queue sub-locations themselves (e.g. boxes set up as locations)", includeLocations) {
            includeLocations = it
        }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { selected?.let { vm.start(it, recursive, includeLocations) } },
                enabled = selected != null && !ui.loading,
                modifier = Modifier.weight(1f),
            ) { Text(selected?.let { "Start: ${it.node.name}" } ?: "Pick a location") }
            OutlinedButton(onClick = { vm.loadLocations() }, enabled = !ui.loading) { Text("Reload") }
        }
        HorizontalDivider()
        if (ui.loading) {
            CircularProgressIndicator(Modifier.padding(24.dp).align(Alignment.CenterHorizontally))
        }
        LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
            items(ui.locations, key = { it.node.id }) { opt ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { selected = opt }
                        .padding(start = (opt.depth * 20).dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected?.node?.id == opt.node.id, onClick = { selected = opt })
                    Text(opt.node.name)
                }
            }
        }
    }
}

@Composable
internal fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun RunView(ui: BatchUi, session: BatchSession, vm: BatchViewModel) {
    val current = session.current
    val total = session.items.size
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(session.rootName, style = MaterialTheme.typography.labelLarge)
        if (total > 0) {
            LinearProgressIndicator(
                progress = { session.index.coerceAtMost(total).toFloat() / total },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("${session.index.coerceAtMost(total)} of $total done in this run", style = MaterialTheme.typography.bodySmall)
        }

        if (current != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (ui.busy) "Updating Homebox…" else "Tap a tag for", style = MaterialTheme.typography.labelLarge)
                    Text(current.name, style = MaterialTheme.typography.headlineMedium)
                    Text(
                        (if (current.isLocation) "Location in " else "In ") + current.path,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("${session.index + 1} of $total", style = MaterialTheme.typography.labelMedium)
                }
            }

            if (ui.pendingMark != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::retryMark, modifier = Modifier.weight(1f)) { Text("Retry Homebox") }
                    OutlinedButton(onClick = vm::continueWithoutMark, modifier = Modifier.weight(1f)) { Text("Continue anyway") }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = vm::back, enabled = session.index > 0 && !ui.busy, modifier = Modifier.weight(1f)) {
                        Text("‹ Previous")
                    }
                    OutlinedButton(onClick = vm::skip, enabled = !ui.busy, modifier = Modifier.weight(1f)) {
                        Text("Skip ›")
                    }
                }
            }

            session.items.getOrNull(session.index + 1)?.let {
                Text("Up next: ${it.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("All done", style = MaterialTheme.typography.headlineSmall)
                    Text("Every entry in this run has been handled. Refresh to check for anything skipped or new.")
                }
            }
            if (session.index > 0) {
                OutlinedButton(onClick = vm::back, enabled = !ui.busy) { Text("‹ Previous") }
            }
        }

        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = vm::refresh, enabled = !ui.busy && !ui.loading) { Text("Refresh from Homebox") }
            TextButton(onClick = vm::endSession, enabled = !ui.busy) { Text("End run", color = Color(0xFFB3261E)) }
        }
        if (ui.loading) CircularProgressIndicator()
    }
}
