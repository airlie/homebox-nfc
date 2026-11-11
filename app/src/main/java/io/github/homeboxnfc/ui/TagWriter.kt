package io.github.homeboxnfc.ui

import android.nfc.Tag
import io.github.homeboxnfc.HomeboxNfcApplication
import io.github.homeboxnfc.nfc.NfcTags
import io.github.homeboxnfc.nfc.TagException
import java.time.LocalDate

/**
 * Writes an entry's link to a tag (shared by Batch write and Write one tag) and queues the
 * matching Homebox update. Guards against two easy mistakes: re-writing the tag you just
 * wrote, and silently overwriting a tag that already holds something (that takes a second tap).
 */
class TagWriter(private val app: HomeboxNfcApplication) {
    sealed interface Result {
        data class Written(val uid: String, val alreadyThere: Boolean) : Result
        data class ConfirmOverwrite(val message: String) : Result
        data object SameAsLast : Result
        data class Failed(val message: String) : Result
    }

    @Volatile private var lastWrittenUid: String? = null
    @Volatile private var armedOverwriteUid: String? = null

    /** Called on the NFC thread while the tag is in the field. */
    fun write(tag: Tag, entryId: String, isLocation: Boolean, name: String): Result {
        val uid = NfcTags.uid(tag)
        if (uid == lastWrittenUid) return Result.SameAsLast
        val prefs = app.prefs
        val url = app.homebox.entityUrl(entryId, isLocation)
        val pkg = prefs.aarPackage.trim().ifBlank { null }
        var alreadyThere = false
        try {
            val snap = NfcTags.read(tag)
            val message = NfcTags.buildMessage(url, pkg, name.takeIf { prefs.includeName }, snap.maxSize)
            alreadyThere = snap.message?.toByteArray()?.contentEquals(message.toByteArray()) == true
            val existing = snap.url
            if (!alreadyThere && existing != null && existing != url && uid != armedOverwriteUid) {
                armedOverwriteUid = uid
                val what = if (app.homebox.isHomeboxUrl(existing)) {
                    val id = app.homebox.parseEntityUrl(existing)?.second
                    id?.let { app.store.view.value?.index?.get(it)?.name }?.let { "the tag for $it" } ?: "a different Homebox entry"
                } else {
                    existing
                }
                return Result.ConfirmOverwrite("This tag already holds $what. Tap it again to overwrite.")
            }
            if (!alreadyThere) NfcTags.write(tag, message)
        } catch (e: TagException) {
            return Result.Failed(e.message.orEmpty())
        }
        armedOverwriteUid = null
        lastWrittenUid = uid
        return Result.Written(uid, alreadyThere)
    }

    /** Allow the last tag to be written again (e.g. after going back to rewrite it). */
    fun forgetLast() {
        lastWrittenUid = null
        armedOverwriteUid = null
    }

    /**
     * Queues "this entry now has an NFC tag" for Homebox: the NFC tag, the tag's UID, and
     * today's date as last verified (you're standing next to it, so it's where it should be).
     */
    fun queueMark(entryId: String, name: String, uid: String) {
        val prefs = app.prefs
        val store = app.store
        val fields = buildMap {
            prefs.nfcFieldName.trim().takeIf { it.isNotBlank() }?.let { put(it, uid) }
            prefs.verifiedFieldName.trim().takeIf { it.isNotBlank() }?.let { put(it, LocalDate.now().toString()) }
        }
        store.enqueue(
            store.newChange(
                entityId = entryId,
                entityName = name,
                summary = "Tag written",
                addTag = prefs.nfcTagName,
                setFields = fields,
            )
        )
    }
}
