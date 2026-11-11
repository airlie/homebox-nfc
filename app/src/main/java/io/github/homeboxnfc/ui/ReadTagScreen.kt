package io.github.homeboxnfc.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.homeboxnfc.HomeboxNfcApplication
import io.github.homeboxnfc.nfc.TagBus

@Composable
fun ReadTagScreen(
    app: HomeboxNfcApplication,
    fix: Boolean = false,
    vm: ReadTagViewModel = viewModel(key = if (fix) "fix" else "read"),
) {
    DisposableEffect(vm) {
        vm.fixMode = fix
        TagBus.handler = vm::onTag
        onDispose { TagBus.handler = null }
    }
    val reports by vm.reports.collectAsStateWithLifecycle()
    val addAar by vm.addAar.collectAsStateWithLifecycle()
    val pkg = app.prefs.aarPackage.trim()

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Hold a tag to the back of the phone.", style = MaterialTheme.typography.titleMedium)
            Text(
                if (fix) {
                    "Updates an old Homebox address and adds the app record" +
                        (if (pkg.isNotBlank()) " ($pkg)" else "") +
                        " in the same tap. Everything else on the tag is kept."
                } else {
                    "Shows what the tag holds and what it's for. Tags aren't changed unless fixing is on."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!fix) {
                ToggleRow(
                    "Fix tags as they're read",
                    "Same as Fix tag: updates an old Homebox address" +
                        (if (pkg.isNotBlank()) " and adds the app record ($pkg)" else "") + ". Other records are kept.",
                    addAar,
                ) { vm.setAddAar(it) }
            }
        }
        items(reports, key = { it.key }) { ReportCard(app, it) }
        if (reports.isNotEmpty()) {
            item { TextButton(onClick = vm::clear) { Text("Clear") } }
        }
    }
}

@Composable
private fun ReportCard(app: HomeboxNfcApplication, r: TagReport) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                r.error != null -> scheme.errorContainer
                r.entry != null -> scheme.secondaryContainer
                else -> scheme.surfaceContainerHigh
            },
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (r.error != null) {
                Text("Couldn't read the tag", style = MaterialTheme.typography.titleMedium)
                Text(r.error, style = MaterialTheme.typography.bodyMedium)
                Text("Tag ${r.uid}", style = MaterialTheme.typography.labelSmall)
                return@Column
            }

            val entry = r.entry
            if (entry != null) {
                val live = entry.live
                Text(
                    live?.name ?: entry.name ?: if (entry.loading) "Looking it up…" else "Unknown Homebox entry",
                    style = MaterialTheme.typography.headlineSmall,
                )
                val kind = if (live?.isLocation ?: entry.isLocation) "Location" else "Item"
                val where = live?.parentName?.takeIf { it.isNotBlank() } ?: entry.path?.takeIf { it.isNotBlank() }
                Text(if (where != null) "$kind in $where" else kind, style = MaterialTheme.typography.bodyLarge)
                if (live != null) {
                    val fieldName = app.prefs.nfcFieldName.trim()
                    if (fieldName.isNotBlank()) {
                        val value = live.field(fieldName)
                        Text(
                            "$fieldName: " + when {
                                value.isNullOrBlank() -> "empty"
                                value.equals(r.uid, ignoreCase = true) -> "this tag"
                                else -> "$value (a different tag)"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    val verifiedName = app.prefs.verifiedFieldName.trim()
                    if (verifiedName.isNotBlank()) {
                        Text("$verifiedName: ${live.field(verifiedName)?.ifBlank { null } ?: "never"}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                entry.liveError?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            } else {
                Text(
                    when {
                        r.records.isEmpty() -> "Empty tag"
                        r.url != null -> "Not a Homebox tag"
                        else -> "No link on this tag"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            r.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }

            if (r.records.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("On the tag", style = MaterialTheme.typography.labelLarge)
                r.records.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            Text(
                listOf("Tag ${r.uid}", r.facts).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
            r.url?.takeIf { it.startsWith("http", ignoreCase = true) }?.let { url ->
                TextButton(
                    onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
                    contentPadding = PaddingValues(0.dp),
                ) { Text("Open link") }
            }
        }
    }
}
