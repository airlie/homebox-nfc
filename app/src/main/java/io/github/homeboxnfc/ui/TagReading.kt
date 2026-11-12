package io.github.homeboxnfc.ui

import android.nfc.NdefMessage
import android.nfc.Tag
import io.github.homeboxnfc.HomeboxNfcApplication
import io.github.homeboxnfc.nfc.NfcTags
import io.github.homeboxnfc.nfc.TagException
import io.github.homeboxnfc.nfc.TagSnapshot

/** A tag as read, after any fixes made in the same tap. */
data class ReadTag(
    val snap: TagSnapshot,
    /** The tag's link (the updated one, if it was updated). */
    val url: String?,
    /** What was fixed, e.g. "Updated the link to the current Homebox address." */
    val fixed: List<String>,
    /** What could be fixed but wasn't (fixing off), or couldn't be (locked, too small...). */
    val problems: List<String>,
) {
    /** A sentence or two to append to a status message, or "". */
    val note: String get() = (fixed + problems).joinToString("") { " $it" }
}

/**
 * Reads [tag] (on the NFC thread, while it's in the field). With [fix], also brings it up to
 * date in the same tap, keeping its other records:
 * - a link to a previous Homebox address (from Settings) is switched to the current address;
 * - the app record (AAR) from Settings is added if the tag has a link but no AAR.
 * Without [fix], those are only reported in [ReadTag.problems]. Throws [TagException] if the
 * tag can't be read.
 */
fun readTag(app: HomeboxNfcApplication, tag: Tag, fix: Boolean): ReadTag {
    val snap = NfcTags.read(tag)
    val oldUrl = snap.url ?: return ReadTag(snap, null, emptyList(), emptyList())
    val fixed = mutableListOf<String>()
    val problems = mutableListOf<String>()

    val newUrl = app.homebox.currentAddressFor(oldUrl)
    val pkg = app.prefs.aarPackage.trim().ifBlank { null }
    val needsAar = pkg != null && !snap.hasAar(pkg)
    if (newUrl == null && !needsAar) return ReadTag(snap, oldUrl, fixed, problems)

    if (!fix) {
        if (newUrl != null) problems += "It links to a previous Homebox address."
        if (needsAar) problems += "It doesn't have the app record ($pkg)."
        return ReadTag(snap, oldUrl, fixed, problems)
    }
    if (!snap.isWritable) {
        problems += "It needs fixing but is locked."
        return ReadTag(snap, oldUrl, fixed, problems)
    }

    // Build the fixed message: swap the address, then add the AAR, each only if it still fits.
    var message: NdefMessage = snap.message ?: return ReadTag(snap, oldUrl, fixed, problems)
    var url = oldUrl
    if (newUrl != null) {
        val swapped = NfcTags.replaceUrl(message, oldUrl, newUrl, snap.maxSize)
        if (swapped == null) {
            problems += "The new Homebox address doesn't fit on the tag."
        } else {
            message = swapped
            url = newUrl
        }
    }
    if (needsAar) {
        val withAar = NfcTags.withAar(message, pkg!!)
        when {
            snap.maxSize == 0 || withAar.byteArrayLength <= snap.maxSize -> message = withAar
            NfcTags.buildMessage(url, pkg).byteArrayLength <= snap.maxSize -> {
                message = NfcTags.buildMessage(url, pkg)
                problems += "No room for the tag's other records, so only the link was kept."
            }
            else -> problems += "No room for the app record."
        }
    }
    if (message.toByteArray().contentEquals(snap.message?.toByteArray())) return ReadTag(snap, oldUrl, fixed, problems)

    return try {
        val back = NfcTags.write(tag, message)
        if (url != oldUrl) fixed += "Updated the tag to the current Homebox address."
        if (needsAar && back.hasAar(pkg!!)) fixed += "Added the app record."
        ReadTag(back.copy(techs = snap.techs), url, fixed, problems)
    } catch (e: TagException) {
        ReadTag(snap, oldUrl, emptyList(), problems + "Couldn't fix it: ${e.message}")
    }
}
