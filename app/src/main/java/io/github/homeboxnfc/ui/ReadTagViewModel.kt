package io.github.homeboxnfc.ui

import android.app.Application
import android.nfc.Tag
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.homeboxnfc.HomeboxNfcApplication
import io.github.homeboxnfc.SpeakMode
import io.github.homeboxnfc.homebox.EntityInfo
import io.github.homeboxnfc.homebox.HomeboxException
import io.github.homeboxnfc.nfc.NfcTags
import io.github.homeboxnfc.nfc.TagException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/** The Homebox entry a tag links to. [live] fills in once Homebox answers (if it's reachable). */
data class TagEntry(
    val id: String,
    val name: String?,
    val isLocation: Boolean,
    /** Where it is, from the saved data. */
    val path: String?,
    val live: EntityInfo? = null,
    val liveError: String? = null,
    val loading: Boolean = false,
)

data class TagReport(
    val key: Long,
    val uid: String,
    val url: String?,
    val entry: TagEntry?,
    /** One line per NDEF record. */
    val records: List<String>,
    /** Type, size, locked: one short line. */
    val facts: String,
    /** Things the app did or noticed (updated the address, added the app record...). */
    val notes: List<String>,
    val error: String? = null,
)

/**
 * Read tag: shows what a tag holds. For a Homebox tag, which entry it's for and where that is;
 * otherwise a plain list of its records. Updates old Homebox addresses as it goes and, if
 * turned on, adds the app record to tags that are missing it.
 */
class ReadTagViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as HomeboxNfcApplication
    private val keys = AtomicLong()

    private val _reports = MutableStateFlow<List<TagReport>>(emptyList())
    val reports: StateFlow<List<TagReport>> = _reports.asStateFlow()

    private val _addAar = MutableStateFlow(app.prefs.readAddsAar)
    val addAar: StateFlow<Boolean> = _addAar.asStateFlow()

    fun setAddAar(on: Boolean) {
        app.prefs.readAddsAar = on
        _addAar.value = on
    }

    /** Fix tag mode: always add the app record, and say so when there was nothing to fix. Set by the screen. */
    @Volatile var fixMode = false

    /** Called on the NFC thread. */
    fun onTag(tag: Tag) {
        val key = keys.incrementAndGet()
        val uid = NfcTags.uid(tag)
        // Read tag only changes tags when its "fix" switch is on; Fix tag always does.
        val fixing = fixMode || _addAar.value
        val read = try {
            readTag(app, tag, fix = fixing)
        } catch (e: TagException) {
            app.feedback.error()
            add(TagReport(key, uid, null, null, emptyList(), "", emptyList(), error = e.message.orEmpty()))
            return
        }
        val snap = read.snap
        val url = read.url
        val pkg = app.prefs.aarPackage.trim()
        val notes = (read.fixed + read.problems.map { if (fixing) it else "$it Fix tag would fix it." }).toMutableList()
        if (fixMode && url == null) notes += "No link on this tag, so there's nothing to fix."

        if (fixMode && notes.isEmpty()) {
            notes += when {
                !snap.isWritable -> "The tag is locked, so it can't be changed."
                pkg.isBlank() -> "Nothing to fix. (No app is set under Tag contents in Settings.)"
                else -> "Nothing to fix. The link is current and the app record is there."
            }
        }

        val entry = url?.let { app.homebox.parseEntityUrl(it) }?.let { (isLocation, id) ->
            val index = app.store.view.value?.index
            val node = index?.get(id)
            TagEntry(
                id = id,
                name = node?.name,
                isLocation = node?.isLocation ?: isLocation,
                path = node?.let { index.containerPath(id) },
                loading = true,
            )
        }

        val used = snap.message?.byteArrayLength ?: 0
        val facts = buildList {
            if (snap.techs.isNotEmpty()) add(snap.techs.filter { it != "Ndef" && it != "NdefFormatable" }.joinToString(", "))
            when {
                !snap.isNdef -> add("blank, not formatted yet")
                snap.maxSize > 0 -> add("$used of ${snap.maxSize} bytes used")
            }
            if (snap.isNdef && !snap.isWritable) add("locked")
        }.filter { it.isNotBlank() }.joinToString(" · ")

        val records = snap.message?.records.orEmpty().map { NfcTags.describe(it) }
        if (fixing && read.problems.isNotEmpty()) app.feedback.warning() else app.feedback.success()
        if (!fixMode) entry?.name?.let { app.speaker.speak(SpeakMode.READ, it) }
        add(TagReport(key, snap.uid, url, entry, records, facts, notes))
        if (entry != null) fetchLive(key, entry.id)
    }

    /** Asks Homebox for the entry's current details. Silent about failures beyond a short note. */
    private fun fetchLive(key: Long, id: String) {
        viewModelScope.launch {
            val (info, error) = try {
                withContext(Dispatchers.IO) { app.homebox.entityInfo(id) } to null
            } catch (e: HomeboxException) {
                null to when (e.kind) {
                    HomeboxException.Kind.NOT_FOUND -> "Homebox doesn't have this entry any more."
                    HomeboxException.Kind.NETWORK -> "Can't reach Homebox right now. Showing saved data."
                    else -> e.message
                }
            } catch (e: Exception) {
                null to (e.message ?: e.javaClass.simpleName)
            }
            val firstTime = _reports.value.firstOrNull { it.key == key }?.entry?.name == null
            _reports.update { list ->
                list.map { r ->
                    if (r.key != key || r.entry == null) r
                    else r.copy(entry = r.entry.copy(live = info, liveError = error, loading = false))
                }
            }
            // Wasn't in the saved data, so nothing was spoken yet.
            if (firstTime && info != null && !fixMode) app.speaker.speak(SpeakMode.READ, info.name)
        }
    }

    private fun add(report: TagReport) = _reports.update { (listOf(report) + it).take(10) }

    fun clear() = _reports.update { emptyList() }
}
