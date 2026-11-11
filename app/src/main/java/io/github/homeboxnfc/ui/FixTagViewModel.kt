package io.github.homeboxnfc.ui

import android.app.Application
import android.nfc.Tag
import androidx.lifecycle.AndroidViewModel
import io.github.homeboxnfc.HomeboxNfcApplication
import io.github.homeboxnfc.nfc.NfcTags
import io.github.homeboxnfc.nfc.TagException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class FixEntry(val ok: Boolean, val title: String, val detail: String, val uid: String)

/**
 * "Fix tag": read whatever URL is on the tag and, if it lacks the Android Application Record,
 * write the same URL back with one. All in a single tap.
 */
class FixTagViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as HomeboxNfcApplication

    private val _log = MutableStateFlow<List<FixEntry>>(emptyList())
    val log: StateFlow<List<FixEntry>> = _log.asStateFlow()

    /** Called on the NFC thread. */
    fun onTag(tag: Tag) {
        val pkg = app.prefs.aarPackage.trim()
        val uid = NfcTags.uid(tag)
        val entry = try {
            val snap = NfcTags.read(tag)
            val url = snap.url
            when {
                url == null -> FixEntry(
                    false, "No URL on this tag",
                    if (snap.isEmpty) "The tag is empty." else "${snap.recordCount} record(s), none of them a URL.",
                    uid,
                )
                pkg.isBlank() -> FixEntry(false, "No app package set", "Set one in Settings (e.g. com.android.chrome).", uid)
                snap.hasAar(pkg) -> FixEntry(true, "Already set up", url, uid)
                !snap.isWritable -> FixEntry(false, "Tag is locked (read-only)", url, uid)
                else -> {
                    // Keep whatever else is on the tag (e.g. a text record) if it still fits.
                    val keepAll = snap.message?.let { NfcTags.withAar(it, pkg) }
                        ?.takeIf { snap.maxSize == 0 || it.byteArrayLength <= snap.maxSize }
                    NfcTags.write(tag, keepAll ?: NfcTags.buildMessage(url, pkg))
                    val note = if (keepAll == null && snap.recordCount > 1) " (no room for the other records)" else ""
                    FixEntry(true, "Added $pkg$note", url, uid)
                }
            }
        } catch (e: TagException) {
            FixEntry(false, "Couldn't fix tag", e.message.orEmpty(), uid)
        }
        if (entry.ok) app.feedback.success() else app.feedback.error()
        _log.update { (listOf(entry) + it).take(20) }
    }
}
