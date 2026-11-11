package io.github.homeboxnfc.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.homeboxnfc.HomeboxNfcApplication
import io.github.homeboxnfc.nfc.TagBus

@Composable
fun WriteOneScreen(app: HomeboxNfcApplication, vm: WriteOneViewModel = viewModel()) {
    DisposableEffect(vm) {
        TagBus.handler = vm::onTag
        onDispose { TagBus.handler = null }
    }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val view by app.store.view.collectAsStateWithLifecycle()
    val results = remember(ui.query, view) { vm.search(ui.query) }
    val focus = LocalFocusManager.current
    val selected = ui.selected

    Column(Modifier.padding(horizontal = 16.dp)) {
        ui.message?.let { MessageCard(it, ui.messageKind) }

        if (selected != null) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Tap a tag for", style = MaterialTheme.typography.labelLarge)
                    Text(selected.name, style = MaterialTheme.typography.headlineMedium)
                    if (selected.path.isNotBlank()) Text("In ${selected.path}", style = MaterialTheme.typography.bodyMedium)
                    if (selected.tagged) {
                        Text("Homebox already lists this as tagged.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            TextButton(onClick = { vm.select(null) }) { Text("Pick something else") }
            return@Column
        }

        OutlinedTextField(
            value = ui.query,
            onValueChange = vm::setQuery,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("Search items and locations") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (ui.query.isNotEmpty()) {
                    IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Filled.Clear, contentDescription = "Clear") }
                }
            },
        )
        SavedDataLine(app)
        if (ui.query.isNotBlank() && results.isEmpty()) {
            Text("Nothing matches.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn {
            items(results, key = { it.id }) { opt ->
                ListItem(
                    modifier = Modifier.clickable { focus.clearFocus(); vm.select(opt) },
                    headlineContent = { Text(opt.name) },
                    supportingContent = { if (opt.path.isNotBlank()) Text(opt.path) },
                    leadingContent = { if (opt.isLocation) Icon(Icons.Filled.Place, contentDescription = "Location") },
                    trailingContent = { if (opt.tagged) Icon(Icons.Filled.Check, contentDescription = "Already tagged") },
                )
            }
        }
    }
}
