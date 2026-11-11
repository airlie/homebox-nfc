package io.github.homeboxnfc.ui

import android.nfc.Tag
import io.github.homeboxnfc.HomeboxNfcApplication
import io.github.homeboxnfc.nfc.NfcTags
import io.github.homeboxnfc.nfc.TagException
import io.github.homeboxnfc.nfc.TagSnapshot

/** A tag as read, after updating a link that pointed at a previous Homebox address. */
data class ReadTag(
    val snap: TagSnapshot,
    /** The tag's link (the updated one, if it was updated). */
    val url: String?,
    /** True if the link was switched to the current address during this read. */
    val updated: Boolean,
    /** Why an old-address link couldn't be updated, if it couldn't. */
    val updateError: String?,
) {
    /** A sentence to append to a status message, or "". */
    val note: String
        get() = when {
            updated -> " Updated the tag to the new Homebox address."
            updateError != null -> " It still has the old Homebox address: $updateError"
            else -> ""
        }
}

/**
 * Reads [tag] (on the NFC thread, while it's in the field). If its link uses one of the
 * previous Homebox addresses from Settings, rewrites it to the current address right away,
 * keeping the tag's other records. Throws [TagException] if the tag can't be read.
 */
fun readTag(app: HomeboxNfcApplication, tag: Tag, updateOldAddress: Boolean = true): ReadTag {
    val snap = NfcTags.read(tag)
    val url = snap.url ?: return ReadTag(snap, null, false, null)
    if (!updateOldAddress) return ReadTag(snap, url, false, null)
    val newUrl = app.homebox.currentAddressFor(url) ?: return ReadTag(snap, url, false, null)
    val message = snap.message
    val replaced = message?.let { NfcTags.replaceUrl(it, url, newUrl, snap.maxSize) }
    return when {
        !snap.isWritable -> ReadTag(snap, url, false, "the tag is locked.")
        replaced == null -> ReadTag(snap, url, false, "the new address doesn't fit on the tag.")
        else -> try {
            val back = NfcTags.write(tag, replaced)
            ReadTag(back.copy(techs = snap.techs), newUrl, true, null)
        } catch (e: TagException) {
            ReadTag(snap, url, false, e.message.orEmpty())
        }
    }
}
