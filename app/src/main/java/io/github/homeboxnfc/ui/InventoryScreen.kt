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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.homeboxnfc.HomeboxNfcApplication
import io.github.homeboxnfc.nfc.TagBus

@Composable
fun InventoryScreen(app: HomeboxNfcApplication, vm: InventoryViewModel = viewModel()) {
    DisposableEffect(vm) {
        TagBus.handler = vm::onTag
        onDispose { TagBus.handler = null }
    }
    val ui by vm.ui.collectAsStateWithLifecycle()
    var showMissing by remember { mutableStateOf(false) }
    val current = ui.current

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { ui.message?.let { MessageCard(it, ui.messageKind) } }

        item {
            ListItem(
                leadingContent = { Icon(Icons.Filled.Place, contentDescription = null) },
                overlineContent = { Text("Current location") },
                headlineContent = { Text(current?.path ?: "None yet", style = MaterialTheme.typography.titleLarge) },
                trailingContent = {
                    TextButton(onClick = { vm.showPicker(!ui.picking) }, enabled = ui.locations.isNotEmpty()) {
                        Text(if (ui.picking && current != null) "Cancel" else "Change")
                    }
                },
            )
            ToggleRow(
                "Count sub-locations as here",
                "An item on a shelf inside this room is verified, not moved",
                ui.acceptSubLocations,
                vm::setAcceptSubLocations,
            )
            ToggleRow(
                "Fix tags as they're scanned",
                "Same as Fix tag: updates an old Homebox address and adds the app record",
                ui.fixTags,
                vm::setFixTags,
            )
            SavedDataLine(app)
        }

        ui.nestOffer?.let { offer ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Switched from ${offer.parent.name} to ${offer.child.name}.")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = vm::acceptNest) { Text("Put it in ${offer.parent.name}") }
                            OutlinedButton(onClick = vm::dismissNest) { Text("Just switch") }
                        }
                    }
                }
            }
        }

        if (ui.picking || current == null) {
            item { HorizontalDivider() }
            items(ui.locations, key = { "loc-" + it.node.id }) { opt ->
                Text(
                    opt.node.name,
                    Modifier
                        .fillMaxWidth()
                        .clickable { vm.pickLocation(opt) }
                        .padding(start = (opt.depth * 20 + 8).dp, top = 12.dp, bottom = 12.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            return@LazyColumn
        }

        item {
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Found ${ui.foundExpectedCount} of ${ui.expected.size} tagged items expected here",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (ui.missing.isNotEmpty()) {
                    TextButton(onClick = { showMissing = !showMissing }) {
                        Text(if (showMissing) "Hide missing" else "Show ${ui.missing.size} missing")
                    }
                }
            }
        }
        if (showMissing) {
            items(ui.missing, key = { "missing-" + it.id }) { node ->
                Text("• ${node.name}", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (ui.log.isNotEmpty()) {
            item { Text("Scans", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
        }
        items(ui.log, key = { it.key }) { entry -> LogRow(entry) { vm.undo(entry) } }
    }
}

@Composable
private fun LogRow(entry: InvEntry, onUndo: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val bg = when (entry.outcome) {
        InvOutcome.VERIFIED -> scheme.primaryContainer
        InvOutcome.MOVED -> scheme.tertiaryContainer
        InvOutcome.LOCATION -> scheme.surfaceContainerHigh
        InvOutcome.ERROR -> scheme.errorContainer
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = bg)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(entry.title, style = MaterialTheme.typography.titleSmall)
                if (entry.detail.isNotBlank()) Text(entry.detail, style = MaterialTheme.typography.bodySmall)
            }
            if (entry.undo != null && !entry.undone) TextButton(onClick = onUndo) { Text("Undo") }
        }
    }
}
