package io.github.homeboxnfc.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.homeboxnfc.HomeboxNfcApplication
import io.github.homeboxnfc.SpeakMode

/** The instruction every location-based mode shows until a location is chosen. */
internal const val PICK_LOCATION_HINT = "Scan a location's tag, or pick one below."

/**
 * The location block shared by Batch write, Inventory and Add items: what's chosen, a
 * Change/Cancel button when [onTogglePicker] is given, and how fresh the saved data is.
 * Goes after the mode's own settings, right above [locationList].
 */
@Composable
internal fun LocationHeader(
    app: HomeboxNfcApplication,
    label: String,
    path: String?,
    picking: Boolean,
    onTogglePicker: (() -> Unit)? = null,
) {
    HorizontalDivider()
    ListItem(
        leadingContent = { Icon(Icons.Filled.Place, contentDescription = null) },
        overlineContent = { Text(label) },
        headlineContent = { Text(path ?: "None picked", style = MaterialTheme.typography.titleLarge) },
        supportingContent = if (path == null || picking) ({ Text(PICK_LOCATION_HINT) }) else null,
        trailingContent = if (path != null && onTogglePicker != null) {
            { TextButton(onClick = onTogglePicker) { Text(if (picking) "Cancel" else "Change") } }
        } else {
            null
        },
    )
    SavedDataLine(app)
}

/** The location tree as a list of radio rows, indented by depth. */
internal fun LazyListScope.locationList(
    locations: List<LocationOption>,
    selectedId: String?,
    onPick: (LocationOption) -> Unit,
) {
    item(key = "loc-divider") { HorizontalDivider() }
    items(locations, key = { "loc-" + it.node.id }) { opt ->
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onPick(opt) }
                .padding(start = (opt.depth * 20).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selectedId == opt.node.id, onClick = { onPick(opt) })
            Text(opt.node.name, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** The "Read names aloud" switch for a mode, mirroring the one in Settings. */
@Composable
internal fun SpeakToggle(app: HomeboxNfcApplication, mode: SpeakMode, subtitle: String) {
    val enabled by app.speaker.enabled.collectAsStateWithLifecycle()
    LaunchedEffect(mode) { app.speaker.prepare(mode) }
    ToggleRow("Read names aloud", subtitle, mode in enabled) { app.speaker.setOn(mode, it) }
}
