package io.github.homeboxnfc.ui

import android.content.Intent
import android.nfc.NfcAdapter
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.homeboxnfc.HomeboxNfcApplication
import io.github.homeboxnfc.SpeakMode

enum class Screen(val title: String) {
    HOME("Homebox NFC"),
    WRITE_ONE("Write one tag"),
    BATCH("Batch write"),
    INVENTORY("Inventory"),
    READ("Read tag"),
    FIX("Fix tag"),
    SYNC("Sync"),
    SETTINGS("Settings"),
    WEB_LOGIN("Sign in"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(activity: ComponentActivity, nfcAdapter: NfcAdapter?) {
    val app = activity.application as HomeboxNfcApplication
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    val goBack = { screen = if (screen == Screen.WEB_LOGIN) Screen.SETTINGS else Screen.HOME }
    BackHandler(enabled = screen != Screen.HOME) { goBack() }

    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }

    MaterialTheme(colorScheme = colors) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(screen.title) },
                    navigationIcon = {
                        if (screen != Screen.HOME) {
                            IconButton(onClick = goBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        }
                    },
                    actions = {
                        speakModeFor(screen)?.let { SpeakAction(app, it) }
                        if (screen != Screen.SYNC && screen != Screen.WEB_LOGIN) SyncAction(app) { screen = Screen.SYNC }
                        if (screen == Screen.HOME) {
                            IconButton(onClick = { screen = Screen.SETTINGS }) {
                                Icon(Icons.Filled.Settings, contentDescription = "Settings")
                            }
                        }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                NfcBanner(nfcAdapter)
                if (screen != Screen.WEB_LOGIN) {
                    AccessBanner(app, signIn = { screen = Screen.WEB_LOGIN }, openSettings = { screen = Screen.SETTINGS })
                }
                Column(Modifier.weight(1f).fillMaxWidth()) {
                    when (screen) {
                        Screen.HOME -> HomeScreen(app) { screen = it }
                        Screen.WRITE_ONE -> WriteOneScreen(app)
                        Screen.BATCH -> BatchScreen(app)
                        Screen.INVENTORY -> InventoryScreen(app)
                        Screen.READ -> ReadTagScreen(app)
                        Screen.FIX -> ReadTagScreen(app, fix = true)
                        Screen.SYNC -> SyncScreen(app) { screen = Screen.SETTINGS }
                        Screen.SETTINGS -> SettingsScreen(activity, app) { screen = Screen.WEB_LOGIN }
                        Screen.WEB_LOGIN -> WebLoginScreen(app) { screen = Screen.SETTINGS }
                    }
                }
            }
        }
    }
}

private fun speakModeFor(screen: Screen): SpeakMode? = when (screen) {
    Screen.BATCH -> SpeakMode.BATCH
    Screen.INVENTORY -> SpeakMode.INVENTORY
    Screen.READ -> SpeakMode.READ
    else -> null
}

/** Top-bar switch for reading names aloud on this screen (remembered per screen). */
@Composable
private fun SpeakAction(app: HomeboxNfcApplication, mode: SpeakMode) {
    val enabled by app.speaker.enabled.collectAsStateWithLifecycle()
    val on = mode in enabled
    LaunchedEffect(mode) { app.speaker.prepare(mode) }
    IconButton(onClick = { app.speaker.setOn(mode, !on) }) {
        Icon(
            if (on) SpeakerIcons.On else SpeakerIcons.Off,
            contentDescription = if (on) "Stop reading names aloud" else "Read names aloud",
        )
    }
}

/** Top-bar sync button: shows how many changes are waiting, or a warning if something needs attention. */
@Composable
private fun SyncAction(app: HomeboxNfcApplication, onClick: () -> Unit) {
    val outbox by app.store.outbox.collectAsStateWithLifecycle()
    val status by app.store.status.collectAsStateWithLifecycle()
    val waiting = outbox.count { it.error == null }
    val trouble = status.needsUser || outbox.any { it.error != null }
    IconButton(onClick = onClick) {
        BadgedBox(badge = { if (waiting > 0 && !trouble) Badge { Text("$waiting") } }) {
            if (trouble) {
                Icon(Icons.Filled.Warning, contentDescription = "Sync needs attention", tint = MaterialTheme.colorScheme.error)
            } else {
                Icon(Icons.Filled.Refresh, contentDescription = if (waiting > 0) "$waiting changes waiting" else "Sync")
            }
        }
    }
}

@Composable
private fun NfcBanner(adapter: NfcAdapter?) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(adapter?.isEnabled == true) }
    LifecycleResumeEffect(adapter) {
        enabled = adapter?.isEnabled == true
        onPauseOrDispose { }
    }
    if (adapter != null && enabled) return
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (adapter == null) "This phone doesn't have NFC." else "NFC is turned off.",
                modifier = Modifier.weight(1f),
            )
            if (adapter != null) {
                Button(onClick = { context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }) { Text("Turn on") }
            }
        }
    }
}

@Composable
private fun HomeScreen(app: HomeboxNfcApplication, navigate: (Screen) -> Unit) {
    val session = remember { BatchSession.fromJson(app.prefs.batchSessionJson) }
    val configured = app.prefs.serverUrl.isNotBlank() && app.homebox.hasToken
    // Opening the app is the moment to grab fresh data before heading somewhere without signal.
    LaunchedEffect(Unit) { app.store.refreshIfOlderThan(60_000) }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        if (!configured) {
            Card(
                Modifier.fillMaxWidth().padding(16.dp).clickable { navigate(Screen.SETTINGS) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Set up Homebox", style = MaterialTheme.typography.titleMedium)
                    Text("Add your Homebox address and log in. Read tag works without it.")
                }
            }
        }
        MenuItem(Icons.Filled.Edit, "Write one tag", "Search for an item or location and write its tag.") {
            navigate(Screen.WRITE_ONE)
        }
        MenuItem(
            Icons.AutoMirrored.Filled.List,
            "Batch write",
            session?.takeIf { it.current != null }
                ?.let { "In progress: ${it.rootName}, ${it.index + 1} of ${it.items.size}. Tap to resume." }
                ?: "Write tags for everything in a location that doesn't have one yet.",
        ) { navigate(Screen.BATCH) }
        MenuItem(
            Icons.Filled.CheckCircle,
            "Inventory",
            "Scan a location, then items. Items are verified, or moved here if Homebox had them elsewhere.",
        ) { navigate(Screen.INVENTORY) }
        MenuItem(Icons.Filled.Search, "Read tag", "See what a tag is for. Tags with an old Homebox address get updated.") {
            navigate(Screen.READ)
        }
        MenuItem(Icons.Filled.Build, "Fix tag", "Update an old Homebox address and add the app record, keeping the rest of the tag.") {
            navigate(Screen.FIX)
        }
        if (configured) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                SavedDataLine(app)
                if (app.prefs.accessMode == io.github.homeboxnfc.data.AccessMode.BROWSER_COOKIE) {
                    // Worth a glance before heading somewhere without signal.
                    Text(
                        "Cloudflare: " + cloudflareSignInText(app).replaceFirstChar { it.lowercase() },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            "While this app is open it handles every tag itself, so tapping a tag won't open a browser.\n" +
                "Version ${appVersion(LocalContext.current)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun MenuItem(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = { Text(subtitle) },
    )
}
