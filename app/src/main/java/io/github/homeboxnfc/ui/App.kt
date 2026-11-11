package io.github.homeboxnfc.ui

import android.content.Intent
import android.nfc.NfcAdapter
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.homeboxnfc.HomeboxNfcApplication

enum class Screen(val title: String) {
    HOME("Homebox NFC"),
    FIX("Fix tag"),
    BATCH("Batch write"),
    INVENTORY("Inventory"),
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
                        if (screen != Screen.HOME) TextButton(onClick = goBack) { Text("‹ Back") }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                NfcBanner(nfcAdapter)
                Column(Modifier.weight(1f).fillMaxWidth()) {
                    when (screen) {
                        Screen.HOME -> HomeScreen(app) { screen = it }
                        Screen.FIX -> FixTagScreen()
                        Screen.BATCH -> BatchScreen()
                        Screen.INVENTORY -> InventoryScreen()
                        Screen.SETTINGS -> SettingsScreen(activity, app) { screen = Screen.WEB_LOGIN }
                        Screen.WEB_LOGIN -> WebLoginScreen(app) { screen = Screen.SETTINGS }
                    }
                }
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
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (adapter == null) "This phone doesn't have NFC." else "NFC is turned off.",
                modifier = Modifier.weight(1f).padding(top = 10.dp),
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
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (app.prefs.serverUrl.isBlank()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Start in Settings", style = MaterialTheme.typography.titleMedium)
                    Text("Enter your Homebox address and log in. Fix tag works without it.")
                }
            }
        }
        MenuButton("Fix tag", "Tap a tag that has a URL. If it's missing the Chrome app record, it gets added in the same tap.") {
            navigate(Screen.FIX)
        }
        MenuButton(
            "Batch write",
            session?.takeIf { it.current != null }
                ?.let { "In progress: ${it.rootName}, ${it.index + 1} of ${it.items.size}. Tap to resume." }
                ?: "Pick a Homebox location and write a tag for each entry that doesn't have one yet.",
        ) { navigate(Screen.BATCH) }
        MenuButton(
            "Inventory",
            "Scan a location tag, then item tags. Items get verified, or moved here if Homebox had them somewhere else.",
        ) { navigate(Screen.INVENTORY) }
        MenuButton("Settings", "Homebox address, login, Cloudflare Access, and what gets written.") {
            navigate(Screen.SETTINGS)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "While this app is open it handles every tag itself, so tapping a tag won't open a browser.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MenuButton(title: String, subtitle: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}
