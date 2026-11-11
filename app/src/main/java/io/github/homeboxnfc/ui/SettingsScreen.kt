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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.homeboxnfc.HomeboxNfcApplication
import io.github.homeboxnfc.SpeakMode
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
    var previousUrls by remember { mutableStateOf(prefs.previousServerUrls) }
    DisposableEffect(Unit) {
        val startUrl = prefs.serverUrl
        onDispose {
            // Moved Homebox? Remember the old address so its tags get updated when read.
            prefs.addPreviousServer(startUrl)
            app.store.onSettingsChanged()
        }
    }
    val speakOn by app.speaker.enabled.collectAsStateWithLifecycle()
    var stopAtSymbol by remember { mutableStateOf(prefs.speakStopAtSymbol) }
    var skipWords by remember { mutableStateOf(prefs.speakSkipWords) }
    var maxChars by remember { mutableStateOf(prefs.speakMaxChars.toString()) }
    var sampleName by remember { mutableStateOf("Box 12 - Christmas lights and ornaments") }
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
        OutlinedTextField(
            value = previousUrls,
            onValueChange = { previousUrls = it; prefs.previousServerUrls = it },
            label = { Text("Previous addresses") },
            placeholder = { Text("https://old-homebox.example.com") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
            supportingText = {
                Text("One per line. Tags that link to these are switched to the address above whenever the app reads them. Changing the address adds the old one here.")
            },
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
                    Text(cloudflareSignInText(app))
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
                        app.store.onSignedIn()
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
                onClick = { homebox.setApiKey(apiKey); apiKey = ""; loggedIn = true; app.store.onSignedIn() },
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
            label = { Text("Custom field for the tag's ID") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = verifiedField,
            onValueChange = { verifiedField = it; prefs.verifiedFieldName = it.trim() },
            label = { Text("Custom field for the date it was last seen in place") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("Set when a tag is written and when Inventory checks the item.") },
        )

        Section("Read names aloud")
        Hint("Says the entry's name so you can find the next thing without looking at the screen. The speaker button on each screen switches it too.")
        listOf(
            SpeakMode.BATCH to ("Batch write" to "The next entry to tag"),
            SpeakMode.INVENTORY to ("Inventory" to "Each location and item as it's scanned"),
            SpeakMode.READ to ("Read tag" to "The entry the tag is for"),
        ).forEach { (mode, text) ->
            ToggleRow(text.first, text.second, mode in speakOn) { app.speaker.setOn(mode, it) }
        }
        ToggleRow(
            "Stop at the first symbol",
            "Reads up to the first character that isn't a letter, number or space.",
            stopAtSymbol,
        ) { stopAtSymbol = it; prefs.speakStopAtSymbol = it }
        OutlinedTextField(
            value = skipWords,
            onValueChange = { skipWords = it; prefs.speakSkipWords = it },
            label = { Text("Skip these words at the start") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("Separated by commas. With \"box\", \"Box 12\" is read as \"12\".") },
        )
        OutlinedTextField(
            value = maxChars,
            onValueChange = { v ->
                maxChars = v.filter { it.isDigit() }.take(3)
                maxChars.toIntOrNull()?.let { prefs.speakMaxChars = it }
            },
            label = { Text("Longest name to read (characters)") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = { Text("Stops at the last whole word that fits. 0 means no limit.") },
        )
        OutlinedTextField(
            value = sampleName,
            onValueChange = { sampleName = it },
            label = { Text("Try a name") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            supportingText = {
                // Re-read on every change of the settings above.
                val spoken = remember(sampleName, stopAtSymbol, skipWords, maxChars) { app.speaker.shorten(sampleName) }
                Text("Read as: \"$spoken\"")
            },
        )
        OutlinedButton(onClick = { app.speaker.say(app.speaker.shorten(sampleName)) }) { Text("Play") }

        Section("About")
        Text("Homebox NFC ${appVersion(activity)}", style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = {
            activity.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(REPO_URL)))
        }) { Text("Source code and releases on GitHub") }
    }
}

private const val REPO_URL = "https://github.com/airlie/homebox-nfc"

/** versionName from the installed package, e.g. "0.2.0" or "0.2.0-dev.14". */
internal fun appVersion(context: android.content.Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
} catch (e: Exception) {
    "?"
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
