package io.github.homeboxnfc.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.homeboxnfc.HomeboxNfcApplication
import io.github.homeboxnfc.data.SecretStore

/**
 * Opens Homebox in a WebView so you can get through the Cloudflare Access login page, then
 * keeps the CF_Authorization cookie for API calls. That cookie expires with the Access
 * session (often 24h), so a client certificate or service token is more convenient long term.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebLoginScreen(app: HomeboxNfcApplication, onDone: () -> Unit) {
    val base = app.homebox.baseUrl
    if (base.isBlank()) {
        Text("Set the Homebox address in Settings first.", Modifier.padding(16.dp))
        return
    }
    Column(Modifier.fillMaxSize()) {
        Text(
            "Sign in to Cloudflare Access. This screen closes by itself once you're through.",
            Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                var finished = false
                CookieManager.getInstance().setAcceptCookie(true)
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            if (finished || !url.startsWith(base)) return
                            val cookie = CookieManager.getInstance().getCookie(base).orEmpty()
                                .split(';')
                                .map { it.trim() }
                                .firstOrNull { it.startsWith("CF_Authorization=") }
                                ?.substringAfter('=')
                            if (!cookie.isNullOrBlank()) {
                                finished = true
                                app.secrets[SecretStore.CF_COOKIE] = cookie
                                onDone()
                            }
                        }
                    }
                    loadUrl(base)
                }
            },
        )
    }
}
