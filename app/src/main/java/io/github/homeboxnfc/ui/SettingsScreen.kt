package io.github.homeboxnfc.ui

import android.app.Activity
import android.net.Uri
import android.security.KeyChain
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.homeboxnfc.HomeboxNfcApplication
import io.github.homeboxnfc.data.AccessMode
import io.github.homeboxnfc.data.SecretStore
import io.github.homeboxnfc.homebox.HomeboxException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MIN_VERSION = "0.27.0"

@Composable
fun SettingsScreen(activity: Activity, app: HomeboxNfcApplication, openWebLogin: () -> Unit) {
    val prefs = app.prefs
    val homebox = app.homebox
    val scope = rememberCoroutineScope()

    var serverUrl by remember { mutableStateOf(prefs.serverUrl) }
    var accessMode by remember { mutableStateOf(prefs.accessMode) }
    var certAlias by remember { mutableStateOf(prefs.certAlias) }
    var cfClientId by remember { mutableStateOf(prefs.cfClientId) }
    var cfClientSecret by remember { mutableStateOf(app.secrets[SecretStore.CF_CLIENT_SECRET].orEmpty()) }
    val hasCfCookie = remember { app.secrets[SecretStore.CF_COOKIE] != null }
    var username by remember { mutableStateOf(prefs.username) }
    var password by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var loggedIn by remember { mutableStateOf(homebox.hasToken) }
    var openWith by remember {
        mutableStateOf(
            when (prefs.aarPackage.trim()) {
                CHROME -> OpenWith.CHROME
                "" -> OpenWith.DEFAULT
                else -> OpenWith.OTHER
            }
        )
    }
    var customPackage by remember { mutableStateOf(prefs.aarPackage.trim().takeIf { it != CHROME }.orEmpty()) }
    var includeName by remember { mutableStateOf(prefs.includeName) }
    DisposableEffect(Unit) { onDispose { app.store.onSettingsChanged() } }
    var tagName by remember { mutableStateOf(prefs.nfcTagName) }
    var fieldName by remember { mutableStateOf(prefs.nfcFieldName) }
    var verifiedField by remember { mutableStateOf(prefs.verifiedFieldName) }

    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun launchTask(block: suspend () -> String) {
        busy = true
        status = null
        scope.launch {
            status = try {
                block()
            } catch (e: HomeboxException) {
                e.message
            } catch (e: Exception) {
                "Error: ${e.message ?: e.javaClass.simpleName}"
            }
            busy = false
        }
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Section("Homebox")
        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it; prefs.serverUrl = it.trim() },
            label = { Text("Address") },
            placeholder = { Text("https://homebox.example.com") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )

        Section("Cloudflare Access")
        AccessMode.entries.forEach { mode ->
            Row(
                Modifier.fillMaxWidth().clickable {
                    accessMode = mode; prefs.accessMode = mode; homebox.resetConnection()
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = accessMode == mode, onClick = {
                    accessMode = mode; prefs.accessMode = mode; homebox.resetConnection()
                })
                Text(mode.label)
            }
        }
        when (accessMode) {
            AccessMode.CLIENT_CERT -> {
                Hint(
                    "Uses a certificate installed under Settings › Security › Encryption & credentials › " +
                        "Install a certificate › VPN & app user certificate (the same one the Home Assistant app can use)."
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = {
                        val uri = serverUrl.trim().takeIf { it.startsWith("http") }?.let { Uri.parse(it) }
                        KeyChain.choosePrivateKeyAlias(
                            activity,
                            { alias ->
                                activity.runOnUiThread {
                                    if (alias != null) {
                                        certAlias = alias
                                        prefs.certAlias = alias
                                        homebox.resetConnection()
                                    }
                                }
                            },
                            null, null, uri, certAlias.ifBlank { null },
                        )
                    }) { Text("Choose certificate") }
                    Text(certAlias.ifBlank { "None chosen" })
                }
            }
            AccessMode.SERVICE_TOKEN -> {
                Hint("Create one in Cloudflare Zero Trust › Access › Service auth, and add a Service Auth policy to the Homebox app.")
                OutlinedTextField(
                    value = cfClientId,
                    onValueChange = { cfClientId = it; prefs.cfClientId = it.trim() },
                    label = { Text("Client ID") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = cfClientSecret,
                    onValueChange = { cfClientSecret = it; app.secrets[SecretStore.CF_CLIENT_SECRET] = it.trim() },
                    label = { Text("Client secret") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            AccessMode.BROWSER_COOKIE -> {
                Hint("Signs in through the normal Access page. You'll need to repeat this when the Access session expires.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = openWebLogin, enabled = serverUrl.isNotBlank()) { Text("Sign in") }
                    Text(if (hasCfCookie) "Signed in" else "Not signed in")
                }
            }
            AccessMode.NONE -> Hint("Pick this if /api/ is reachable without Access (for example, a bypass rule for /api/*).")
        }

        Section("Homebox login")
        if (loggedIn) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Logged in", Modifier.weight(1f))
                OutlinedButton(onClick = { homebox.logout(); loggedIn = false }) { Text("Log out") }
            }
        } else {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it; prefs.username = it.trim() },
                label = { Text("Username / email") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text("Password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    launchTask {
                        val msg = withContext(Dispatchers.IO) { homebox.login(username.trim(), password) }
                        password = ""
                        loggedIn = true
                        msg
                    }
                },
                enabled = !busy && username.isNotBlank() && password.isNotEmpty(),
            ) { Text("Log in") }
            Hint("Your password isn't stored. The app creates a Homebox API key for itself, which you can revoke in Homebox.")
            OutlinedTextField(
                value = apiKey, onValueChange = { apiKey = it },
                label = { Text("…or paste an API key") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                onClick = { homebox.setApiKey(apiKey); apiKey = ""; loggedIn = true },
                enabled = apiKey.isNotBlank(),
            ) { Text("Use API key") }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                launchTask {
                    withContext(Dispatchers.IO) {
                        val version = homebox.serverVersion()
                        val who = if (homebox.hasToken) homebox.whoAmI() else null
                        buildString {
                            append("Connected to Homebox ${version ?: "(unknown version)"}")
                            if (version != null && isOlder(version, MIN_VERSION)) {
                                append(". Warning: this app needs Homebox $MIN_VERSION or newer.")
                            }
                            append(if (who != null) ", logged in as $who." else ". Not logged in yet.")
                        }
                    }
                }
            }, enabled = !busy) { Text("Test connection") }
            if (busy) CircularProgressIndicator(Modifier.padding(4.dp))
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

        Section("Tag contents")
        Hint("Every tag gets a link to the entry's Homebox page.")
        Text("When the tag is tapped, open the link in", style = MaterialTheme.typography.bodyLarge)
        fun setOpenWith(mode: OpenWith) {
            openWith = mode
            prefs.aarPackage = when (mode) {
                OpenWith.CHROME -> CHROME
                OpenWith.DEFAULT -> ""
                OpenWith.OTHER -> customPackage.trim()
            }
        }
        OpenWith.entries.forEach { mode ->
            Row(
                Modifier.fillMaxWidth().clickable { setOpenWith(mode) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = openWith == mode, onClick = { setOpenWith(mode) })
                Column {
                    Text(mode.label)
                    Text(mode.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (openWith == OpenWith.OTHER) {
            OutlinedTextField(
                value = customPackage,
                onValueChange = { customPackage = it; prefs.aarPackage = it.trim() },
                label = { Text("App package name") },
                placeholder = { Text("org.mozilla.firefox") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        }
        ToggleRow(
            "Write the entry's name on the tag",
            "Adds a text record so NFC reader apps show what the tag is for. Shortened if the tag is small.",
            includeName,
        ) { includeName = it; prefs.includeName = it }

        Section("Homebox updates")
        Hint("Recorded in Homebox when a tag is written or an item is checked in Inventory. Leave a box empty to skip it.")
        OutlinedTextField(
            value = tagName,
            onValueChange = { tagName = it; prefs.nfcTagName = it.trim() },
            label = { Text("Homebox tag for entries with an NFC tag") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("Batch write uses it to skip entries that already have a tag.") },
        )
        OutlinedTextField(
            value = fieldName,
            onValueChange = { fieldName = it; prefs.nfcFieldName = it.trim() },
            label = { Text("Custom field for the tag's ID and date") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = verifiedField,
            onValueChange = { verifiedField = it; prefs.verifiedFieldName = it.trim() },
            label = { Text("Custom field for the date Inventory last saw it") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private const val CHROME = "com.android.chrome"

private enum class OpenWith(val label: String, val detail: String) {
    CHROME("Chrome", "Opens straight away with no app chooser. Recommended."),
    DEFAULT("Default browser", "Link only. Android uses your default browser, or asks which app to use."),
    OTHER("Another app", "Forces a specific app, by package name."),
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 16.dp, bottom = 4.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Compares "v0.27.1"-style versions; unparseable versions (e.g. "nightly") count as new enough. */
internal fun isOlder(version: String, min: String): Boolean {
    fun parse(v: String) = v.trim().removePrefix("v").split('.', '-').take(3).map { it.toIntOrNull() }
    val a = parse(version)
    val b = parse(min)
    if (a.size < 3 || a.any { it == null }) return false
    for (i in 0 until 3) {
        val x = a[i]!!
        val y = b[i]!!
        if (x != y) return x < y
    }
    return false
}
