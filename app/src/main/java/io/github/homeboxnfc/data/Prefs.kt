package io.github.homeboxnfc.data

import android.content.Context
import android.content.SharedPreferences
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** How the app gets past a Cloudflare Access (or similar) gate in front of Homebox. */
enum class AccessMode(val label: String) {
    NONE("None (Homebox is directly reachable)"),
    CLIENT_CERT("Client certificate (mTLS)"),
    SERVICE_TOKEN("Cloudflare service token"),
    BROWSER_COOKIE("Sign in with browser"),
}

/** Non-secret settings. Secrets live in [SecretStore]. */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Base URL of Homebox, without trailing slash, e.g. https://homebox.example.com */
    var serverUrl by string("serverUrl", "")

    /** Earlier Homebox addresses, one per line. Tags that link to these get updated to [serverUrl]. */
    var previousServerUrls by string("previousServerUrls", "")

    val previousBases: List<String>
        get() = previousServerUrls.lines().map { it.trim().trimEnd('/') }.filter { it.isNotBlank() }

    /** Remembers [old] as a previous address (after the address was changed in Settings). */
    fun addPreviousServer(old: String) {
        val base = old.trim().trimEnd('/')
        val current = serverUrl.trim().trimEnd('/')
        if (base.isBlank() || base.equals(current, ignoreCase = true)) return
        if (previousBases.any { it.equals(base, ignoreCase = true) }) return
        previousServerUrls = (previousBases + base).joinToString("\n")
    }
    var accessModeName by string("accessMode", AccessMode.NONE.name)
    var certAlias by string("certAlias", "")
    var cfClientId by string("cfClientId", "")
    var username by string("username", "")

    /** Package for the Android Application Record. Blank = write the URL only. */
    var aarPackage by string("aarPackage", "com.android.chrome")

    /** Also write the entry's name to the tag as an NDEF text record. */
    var includeName by bool("includeName", false)

    /** Homebox tag added to every item that gets an NFC tag. Blank = don't tag. */
    var nfcTagName by string("nfcTagName", "NFC Tagged")

    /** Custom field that records the NFC tag UID + date. Blank = don't write a field. */
    var nfcFieldName by string("nfcFieldName", "NFC Tag")

    /** Custom field Inventory mode sets to today's date. Blank = don't record. */
    var verifiedFieldName by string("verifiedFieldName", "Location verified")

    /** Read tag: fix tags as they are read (update an old address, add the app record). */
    var readAddsAar by bool("readAddsAar", false)

    // Reading names aloud: a switch per screen, plus how names get shortened.
    var speakBatch by bool("speakBatch", false)
    var speakInventory by bool("speakInventory", false)
    var speakRead by bool("speakRead", false)
    var speakStopAtSymbol by bool("speakStopAtSymbol", true)
    var speakSkipWords by string("speakSkipWords", "box, bin, tote, crate, bag")
    var speakMaxChars by int("speakMaxChars", 20)

    var accessMode: AccessMode
        get() = AccessMode.entries.firstOrNull { it.name == accessModeName } ?: AccessMode.NONE
        set(value) { accessModeName = value.name }

    /** Batch session persisted as JSON so an interrupted run can resume. */
    var batchSessionJson by string("batchSession", "")

    private fun bool(key: String, default: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean = sp.getBoolean(key, default)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
            sp.edit().putBoolean(key, value).apply()
        }
    }

    private fun int(key: String, default: Int) = object : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Int = sp.getInt(key, default)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
            sp.edit().putInt(key, value).apply()
        }
    }

    private fun string(key: String, default: String) = object : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): String = sp.getString(key, default) ?: default
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
            sp.edit().putString(key, value).apply()
        }
    }
}
