package io.github.homeboxnfc.nfc

import android.nfc.FormatException
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import java.io.IOException

/** What we found on a tag. */
data class TagSnapshot(
    val uid: String,
    /** First URI on the tag (URI record, smart poster or absolute-URI record), if any. */
    val url: String?,
    /** Package names from Android Application Records ("android.com:pkg"). */
    val aarPackages: List<String>,
    val recordCount: Int,
    val isNdef: Boolean,
    val isWritable: Boolean,
    /** Usable NDEF capacity in bytes (0 if unknown, e.g. a blank unformatted tag). */
    val maxSize: Int,
    /** The raw message, if the tag had one. */
    val message: NdefMessage? = null,
) {
    val isEmpty: Boolean get() = recordCount == 0
    fun hasAar(pkg: String) = aarPackages.any { it == pkg }
}

class TagException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Reading and writing NDEF tags. All functions do blocking I/O and must be called
 * from the reader-mode callback thread while the tag is still in the field.
 */
object NfcTags {
    private const val AAR_TYPE = "android.com:pkg"

    fun uid(tag: Tag): String = tag.id.joinToString(":") { "%02X".format(it) }

    /**
     * The record set this app writes: the URL (always first, since Android dispatches on the
     * first record), optionally a text record with [label], then optionally an Android
     * Application Record. If [maxSize] is known, the label is shortened (or dropped) to fit.
     */
    fun buildMessage(url: String, aarPackage: String?, label: String? = null, maxSize: Int = 0): NdefMessage {
        fun make(text: String?) = NdefMessage(
            buildList {
                add(NdefRecord.createUri(url))
                if (!text.isNullOrBlank()) add(NdefRecord.createTextRecord(null, text))
                if (!aarPackage.isNullOrBlank()) add(NdefRecord.createApplicationRecord(aarPackage))
            }.toTypedArray()
        )
        var text = label?.trim()?.take(100)
        var message = make(text)
        while (maxSize > 0 && message.byteArrayLength > maxSize && !text.isNullOrEmpty()) {
            text = text.dropLast(1).trimEnd().takeIf { it.length >= 3 }
            message = make(text)
        }
        return message
    }

    /** [existing] with an Android Application Record for [pkg] added (other AARs removed). */
    fun withAar(existing: NdefMessage, pkg: String): NdefMessage {
        val kept = existing.records.filterNot { isAar(it) }
        return NdefMessage((kept + NdefRecord.createApplicationRecord(pkg)).toTypedArray())
    }

    private fun isAar(r: NdefRecord) =
        r.tnf == NdefRecord.TNF_EXTERNAL_TYPE && String(r.type, Charsets.US_ASCII) == AAR_TYPE

    fun read(tag: Tag): TagSnapshot {
        val uid = uid(tag)
        val ndef = Ndef.get(tag)
        if (ndef == null) {
            val formattable = NdefFormatable.get(tag) != null
            if (!formattable) throw TagException("This tag doesn't support NDEF, so it can't hold a URL.")
            return TagSnapshot(uid, null, emptyList(), 0, isNdef = false, isWritable = true, maxSize = 0)
        }
        return guard {
            ndef.connect()
            try {
                val message: NdefMessage? = ndef.ndefMessage
                parse(uid, message, ndef.isWritable, ndef.maxSize)
            } finally {
                closeQuietly(ndef)
            }
        }
    }

    /**
     * Writes [message] and reads it back to confirm it landed. Returns the snapshot read back
     * (or a synthesized one for freshly formatted tags, which can't be re-read in the same tap).
     */
    fun write(tag: Tag, message: NdefMessage): TagSnapshot {
        val uid = uid(tag)
        val size = message.byteArrayLength
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            return guard {
                ndef.connect()
                try {
                    if (!ndef.isWritable) throw TagException("This tag is locked (read-only).")
                    if (size > ndef.maxSize) {
                        throw TagException("Too much data: needs $size bytes, the tag holds ${ndef.maxSize}.")
                    }
                    ndef.writeNdefMessage(message)
                    val back = ndef.ndefMessage
                    if (back == null || !back.toByteArray().contentEquals(message.toByteArray())) {
                        throw TagException("Wrote the tag but the read-back didn't match. Try again.")
                    }
                    parse(uid, back, ndef.isWritable, ndef.maxSize)
                } finally {
                    closeQuietly(ndef)
                }
            }
        }
        val formattable = NdefFormatable.get(tag)
            ?: throw TagException("This tag doesn't support NDEF, so it can't hold a URL.")
        return guard {
            formattable.connect()
            try {
                formattable.format(message)
            } finally {
                closeQuietly(formattable)
            }
            parse(uid, message, writable = true, maxSize = 0)
        }
    }

    private fun parse(uid: String, message: NdefMessage?, writable: Boolean, maxSize: Int): TagSnapshot {
        val records = message?.records.orEmpty()
        var url: String? = null
        val packages = mutableListOf<String>()
        for (r in records) {
            if (isAar(r)) {
                packages += String(r.payload, Charsets.UTF_8)
                continue
            }
            if (url == null) url = r.toUri()?.toString()
        }
        return TagSnapshot(uid, url, packages, records.size, isNdef = true, isWritable = writable, maxSize = maxSize, message = message)
    }

    private inline fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: TagException) {
        throw e
    } catch (e: TagLostException) {
        throw TagException("The tag moved away too soon. Hold it still against the phone and try again.", e)
    } catch (e: FormatException) {
        throw TagException("The tag's data is malformed: ${e.message}", e)
    } catch (e: IOException) {
        throw TagException("Couldn't talk to the tag (${e.message ?: e.javaClass.simpleName}). Try again.", e)
    } catch (e: SecurityException) {
        // Thrown when the Tag object is stale (tag left the field and came back).
        throw TagException("Lost the tag. Tap it again.", e)
    }

    private fun closeQuietly(c: java.io.Closeable) {
        try { c.close() } catch (_: IOException) { }
    }
}
