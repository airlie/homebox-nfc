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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.homeboxnfc.HomeboxNfcApplication
import io.github.homeboxnfc.data.AccessMode
import io.github.homeboxnfc.data.SecretStore
import io.github.homeboxnfc.homebox.CloudflareSession
import kotlinx.coroutines.delay

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

/** "Signed in until 6:42 PM", "Signed in until Nov 12, 9:00 AM", "Expired" or "Not signed in". */
internal fun cloudflareSignInText(app: HomeboxNfcApplication): String {
    val cookie = app.secrets[SecretStore.CF_COOKIE]
    if (cookie.isNullOrBlank()) return "Not signed in"
    if (CloudflareSession.isExpired(cookie)) return "Sign-in expired"
    val exp = CloudflareSession.expiresAt(cookie) ?: return "Signed in"
    return "Signed in until " + whenText(app, exp)
}

private fun whenText(app: HomeboxNfcApplication, time: Long): String {
    val flags = if (DateUtils.isToday(time)) DateUtils.FORMAT_SHOW_TIME
    else DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
    return DateUtils.formatDateTime(app, time, flags)
}

/**
 * Shown under the top bar on every screen when Homebox can't be reached for a reason the
 * user has to fix: an expired Cloudflare sign-in, or a login/Access problem a request ran into.
 */
@Composable
internal fun AccessBanner(app: HomeboxNfcApplication, signIn: () -> Unit, openSettings: () -> Unit) {
    val status by app.store.status.collectAsStateWithLifecycle()
    val outbox by app.store.outbox.collectAsStateWithLifecycle()
    // Re-check the cookie's expiry every 30 seconds.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    if (app.prefs.serverUrl.isBlank()) return
    val cookieMode = app.prefs.accessMode == AccessMode.BROWSER_COOKIE
    val cookie = app.secrets[SecretStore.CF_COOKIE]
    val cookieExpired = cookieMode && CloudflareSession.isExpired(cookie, now)
    if (!cookieExpired && !status.needsUser) return

    val waiting = outbox.count { it.error == null }
    val headline = when {
        cookieExpired && cookie.isNullOrBlank() -> "Not signed in to Cloudflare"
        cookieExpired -> "Cloudflare sign-in expired"
        else -> status.problem ?: "Homebox needs attention"
    }
    val detail = buildString {
        append(if (waiting > 0) "$waiting change(s) are saved on the phone and will be sent after you sign in." else "Scanning still works from saved data.")
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.Warning, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text(headline, style = MaterialTheme.typography.titleSmall)
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
            if (cookieMode) Button(onClick = signIn) { Text("Sign in") }
            else TextButton(onClick = openSettings) { Text("Settings") }
        }
    }
}
