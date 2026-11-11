package io.github.homeboxnfc.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.homeboxnfc.nfc.TagBus

@Composable
fun FixTagScreen(vm: FixTagViewModel = viewModel()) {
    DisposableEffect(vm) {
        TagBus.handler = vm::onTag
        onDispose { TagBus.handler = null }
    }
    val log by vm.log.collectAsStateWithLifecycle()

    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("Hold a tag to the back of the phone.", style = MaterialTheme.typography.titleMedium)
            Text(
                "The URL on the tag stays the same. If the tag doesn't have the app record yet, " +
                    "it's added in the same tap. Tags that already have it are left alone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(log) { entry ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (entry.ok) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text((if (entry.ok) "✓ " else "✗ ") + entry.title, style = MaterialTheme.typography.titleSmall)
                    if (entry.detail.isNotBlank()) Text(entry.detail, style = MaterialTheme.typography.bodySmall)
                    Text("Tag ${entry.uid}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
