package io.github.homeboxnfc.ui

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.homeboxnfc.HomeboxNfcApplication

/** The big status card at the top of the scanning screens. */
@Composable
internal fun MessageCard(text: String, kind: MessageKind) {
    val scheme = MaterialTheme.colorScheme
    val (bg, fg) = when (kind) {
        MessageKind.INFO -> scheme.surfaceContainerHigh to scheme.onSurface
        MessageKind.SUCCESS -> scheme.primaryContainer to scheme.onPrimaryContainer
        MessageKind.WARNING -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        MessageKind.ERROR -> scheme.errorContainer to scheme.onErrorContainer
    }
    val icon = when (kind) {
        MessageKind.INFO -> Icons.Filled.Info
        MessageKind.SUCCESS -> Icons.Filled.CheckCircle
        MessageKind.WARNING, MessageKind.ERROR -> Icons.Filled.Warning
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = bg, contentColor = fg),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null)
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** A settings-style row: title, supporting text, and a switch. The whole row is tappable. */
@Composable
internal fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** "Homebox data from 5 minutes ago" with a refresh button. */
@Composable
internal fun SavedDataLine(app: HomeboxNfcApplication) {
    val view by app.store.view.collectAsStateWithLifecycle()
    val status by app.store.status.collectAsStateWithLifecycle()
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = when {
                status.refreshing -> "Updating from Homebox…"
                view == null -> "No saved Homebox data yet"
                else -> "Homebox data from " + ago(view!!.fetchedAt) +
                    (status.refreshError?.let { " (offline)" } ?: "")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (status.refreshing) {
            CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
        } else {
            IconButton(onClick = { app.store.refreshIfOlderThan(0) }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh from Homebox")
            }
        }
    }
}

internal fun ago(time: Long): String =
    if (System.currentTimeMillis() - time < 60_000) "just now" else DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
