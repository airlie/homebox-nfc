package io.github.homeboxnfc.homebox

import android.util.Base64
import org.json.JSONObject

/** Reads the expiry out of a Cloudflare Access CF_Authorization cookie (a JWT). */
object CloudflareSession {
    /** Expiry in epoch milliseconds, or null if the cookie can't be read. */
    fun expiresAt(cookie: String?): Long? {
        if (cookie.isNullOrBlank()) return null
        return try {
            val payload = cookie.split('.').getOrNull(1) ?: return null
            val json = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
            JSONObject(json).optLong("exp", 0).takeIf { it > 0 }?.times(1000)
        } catch (e: Exception) {
            null
        }
    }

    /** True if there's no cookie, or its expiry has passed. Unreadable cookies count as valid. */
    fun isExpired(cookie: String?, now: Long = System.currentTimeMillis()): Boolean {
        if (cookie.isNullOrBlank()) return true
        val exp = expiresAt(cookie) ?: return false
        return exp <= now
    }
}
