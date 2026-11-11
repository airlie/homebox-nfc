package io.github.homeboxnfc.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.homeboxnfc.HomeboxNfcApplication
import io.github.homeboxnfc.data.Change

/** Pending Homebox updates and the saved data, with manual controls. */
@Composable
fun SyncScreen(app: HomeboxNfcApplication, openSettings: () -> Unit) {
    val store = app.store
    val outbox by store.outbox.collectAsStateWithLifecycle()
    val status by store.status.collectAsStateWithLifecycle()
    val view by store.view.collectAsStateWithLifecycle()
    var discarding by remember { mutableStateOf<Change?>(null) }
    val waiting = outbox.count { it.error == null }
    val failed = outbox.count { it.error != null }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            val (text, kind) = when {
                status.syncing -> "Sending changes to Homebox…" to MessageKind.INFO
                waiting == 0 && failed == 0 -> "Everything is up to date in Homebox." to MessageKind.SUCCESS
                status.problem != null -> "$waiting waiting. ${status.problem}" to
                    (if (status.needsUser) MessageKind.ERROR else MessageKind.WARNING)
                waiting > 0 -> "$waiting waiting to be sent." to MessageKind.INFO
                else -> "$failed change(s) Homebox refused. Retry or discard them below." to MessageKind.ERROR
            }
            MessageCard(text, kind)
            if (waiting > 0 && !status.needsUser) {
                Text(
                    "Changes are sent automatically when Homebox is reachable, even if you close the app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = { store.syncSoon() }, enabled = waiting > 0 && !status.syncing) { Text("Send now") }
                if (status.needsUser) OutlinedButton(onClick = openSettings) { Text("Open Settings") }
            }
        }

        item {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Saved Homebox data", style = MaterialTheme.typography.titleSmall)
            view?.let {
                Text(
                    "${it.index.locationOptions.size} locations, ${it.index.itemCount} items, ${it.taggedIds.size} tagged",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            SavedDataLine(app)
            status.refreshError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }

        if (outbox.isNotEmpty()) {
            item {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Waiting changes", style = MaterialTheme.typography.titleSmall)
            }
        }
        items(outbox, key = { it.id }) { c ->
            if (c.error == null) {
                ListItem(
                    headlineContent = { Text(c.entityName) },
                    supportingContent = { Text(c.summary) },
                    trailingContent = { Text(ago(c.createdAt), style = MaterialTheme.typography.labelSmall) },
                )
            } else {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp).fillMaxWidth()) {
                        Text(c.entityName, style = MaterialTheme.typography.titleSmall)
                        Text(c.summary, style = MaterialTheme.typography.bodyMedium)
                        Text(c.error, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { store.retry(c.id) }) { Text("Retry") }
                            TextButton(onClick = { discarding = c }) { Text("Discard") }
                        }
                    }
                }
            }
        }
    }

    discarding?.let { c ->
        AlertDialog(
            onDismissRequest = { discarding = null },
            title = { Text("Discard this change?") },
            text = { Text("\"${c.summary}\" for ${c.entityName} won't be sent to Homebox. Tags already written aren't affected.") },
            confirmButton = { TextButton(onClick = { store.discard(c.id); discarding = null }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { discarding = null }) { Text("Cancel") } },
        )
    }
}
