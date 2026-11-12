package io.github.homeboxnfc.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.homeboxnfc.HomeboxNfcApplication
import io.github.homeboxnfc.nfc.TagBus

@Composable
fun AddScreen(app: HomeboxNfcApplication, vm: AddViewModel = viewModel()) {
    DisposableEffect(vm) {
        TagBus.handler = vm::onTag
        onDispose { TagBus.handler = null }
    }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Camera: started whenever the view model asks for a photo.
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken -> vm.onPhotoResult(taken) }
    LaunchedEffect(ui.photoRequest) {
        val req = ui.photoRequest ?: return@LaunchedEffect
        vm.photoStarted(req)
        try {
            camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", req.file))
        } catch (e: Exception) {
            vm.onPhotoResult(false) // no camera app
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { ui.message?.let { MessageCard(it, ui.messageKind) } }
        item {
            ListItem(
                leadingContent = { Icon(Icons.Filled.Place, contentDescription = null) },
                overlineContent = { Text("Adding to") },
                headlineContent = { Text(ui.location?.path ?: "Pick a location", style = MaterialTheme.typography.titleLarge) },
                trailingContent = {
                    if (ui.location != null) {
                        TextButton(onClick = { vm.showPicker(!ui.picking) }) { Text(if (ui.picking) "Cancel" else "Change") }
                    }
                },
            )
            OutlinedTextField(
                value = ui.name,
                onValueChange = vm::setName,
                label = { Text("Name for the next item") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Leave empty for a short name like \"Item 3f9c\" that you can change in Homebox later.") },
            )
            ToggleRow("Take a photo of each item", "Opens the camera after the tag is written", ui.photo, vm::setPhoto)
            Text(
                "Adding needs a connection to Homebox, since Homebox gives each new item its ID.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (ui.picking) {
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

        if (ui.added.isNotEmpty()) {
            item { Text("Added", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
        }
        items(ui.added, key = { it.key }) { item ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "In ${item.locationName}" + when (item.photo) {
                                PhotoState.NONE -> ""
                                PhotoState.TAKING -> " · taking photo"
                                PhotoState.UPLOADING -> " · uploading photo…"
                                PhotoState.DONE -> " · photo added"
                                PhotoState.FAILED -> " · photo failed: ${item.photoError}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    when (item.photo) {
                        PhotoState.FAILED -> TextButton(onClick = { vm.retryPhoto(item) }) { Text("Retry") }
                        PhotoState.NONE -> TextButton(onClick = { vm.retakePhoto(item) }) { Text("Photo") }
                        else -> Unit
                    }
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(app.homebox.entityUrl(item.id, false))))
                        }
                    }) { Text("Open") }
                }
            }
        }
    }
}
