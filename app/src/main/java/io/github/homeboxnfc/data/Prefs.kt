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
    var accessModeName by string("accessMode", AccessMode.NONE.name)
    var certAlias by string("certAlias", "")
    var cfClientId by string("cfClientId", "")
    var username by string("username", "")

    /** Package for the Android Application Record. Blank = write the URL only. */
    var aarPackage by string("aarPackage", "com.android.chrome")

    /** Homebox tag added to every item that gets an NFC tag. */
    var nfcTagName by string("nfcTagName", "NFC Tagged")

    /** Custom field that records the NFC tag UID + date. Blank = don't write a field. */
    var nfcFieldName by string("nfcFieldName", "NFC Tag")

    /** Custom field Inventory mode sets to today's date. Blank = don't record. */
    var verifiedFieldName by string("verifiedFieldName", "Location verified")

    var accessMode: AccessMode
        get() = AccessMode.entries.firstOrNull { it.name == accessModeName } ?: AccessMode.NONE
        set(value) { accessModeName = value.name }

    /** Batch session persisted as JSON so an interrupted run can resume. */
    var batchSessionJson by string("batchSession", "")

    private fun string(key: String, default: String) = object : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): String = sp.getString(key, default) ?: default
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
            sp.edit().putString(key, value).apply()
        }
    }
}
