package io.github.homeboxnfc.ui

import android.app.Application
import android.nfc.Tag
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.homeboxnfc.HomeboxNfcApplication
import io.github.homeboxnfc.homebox.HomeboxException
import io.github.homeboxnfc.homebox.TreeNode
import io.github.homeboxnfc.nfc.NfcTags
import io.github.homeboxnfc.nfc.TagException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong

data class InvLocation(val id: String, val name: String, val path: String)

enum class InvOutcome { VERIFIED, MOVED, LOCATION, WARNING, ERROR }

/** Enough to put an entity back where it was. */
data class UndoMove(val entityId: String, val parentId: String?, val locationOverrideId: String?)

data class InvEntry(
    val key: Long,
    val entityId: String?,
    val title: String,
    val detail: String,
    val outcome: InvOutcome,
    val undo: UndoMove? = null,
    val undone: Boolean = false,
)

/** Offered after scanning a location tag while another location was current. */
data class NestOffer(val child: InvLocation, val parent: InvLocation)

data class InventoryUi(
    val loading: Boolean = false,
    val locations: List<LocationOption> = emptyList(),
    val current: InvLocation? = null,
    val picking: Boolean = false,
    val acceptSubLocations: Boolean = true,
    /** NFC-tagged items Homebox says are at the current location. */
    val expected: List<TreeNode> = emptyList(),
    /** Items scanned (and verified or moved) at the current location. */
    val found: Set<String> = emptySet(),
    val log: List<InvEntry> = emptyList(),
    val nestOffer: NestOffer? = null,
    val message: String? = null,
    val messageKind: MessageKind = MessageKind.INFO,
) {
    val missing: List<TreeNode> get() = expected.filter { it.id !in found }
    val foundExpectedCount: Int get() = expected.count { it.id in found }
}

/**
 * Inventory: scan a location tag (or pick a location), then scan item tags. Each item is
 * verified in place or moved here, gets today's date in the "Location verified" field, and
 * gets the NFC Tagged tag / NFC Tag field if it was missing them.
 */
class InventoryViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as HomeboxNfcApplication
    private val prefs = app.prefs
    private val homebox = app.homebox

    private val _ui = MutableStateFlow(InventoryUi())
    val ui: StateFlow<InventoryUi> = _ui.asStateFlow()

    /** Serializes Homebox work so scans are processed in order. */
    private val mutex = Mutex()
    private val keys = AtomicLong()

    @Volatile private var index: TreeIndex? = null
    @Volatile private var taggedIds: Set<String> = emptySet()
    @Volatile private var nfcTagId: String? = null

    init {
        reload()
    }

    // ---- data ---------------------------------------------------------------------------

    fun reload() {
        _ui.update { it.copy(loading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                try {
                    loadTreeLocked()
                    _ui.update { it.copy(loading = false, picking = it.current == null) }
                    if (_ui.value.current == null) say("Scan a location tag, or pick a location below.", MessageKind.INFO)
                } catch (e: HomeboxException) {
                    _ui.update { it.copy(loading = false) }
                    say(e.message.orEmpty(), MessageKind.ERROR)
                }
            }
        }
    }

    private fun loadTreeLocked() {
        val idx = TreeIndex(homebox.tree())
        val tagId = homebox.findTag(prefs.nfcTagName).also { nfcTagId = it }
        taggedIds = tagId?.let { homebox.entityIdsWithTag(it) } ?: emptySet()
        index = idx
        _ui.update { s -> s.copy(locations = idx.locationOptions, expected = expectedFor(s.current, s.acceptSubLocations)) }
    }

    private fun expectedFor(loc: InvLocation?, includeSub: Boolean): List<TreeNode> {
        val idx = index ?: return emptyList()
        if (loc == null) return emptyList()
        return idx.itemsAt(loc.id, includeSub).filter { it.id in taggedIds }
    }

    fun setAcceptSubLocations(value: Boolean) {
        _ui.update { it.copy(acceptSubLocations = value, expected = expectedFor(it.current, value)) }
    }

    fun showPicker(show: Boolean) = _ui.update { it.copy(picking = show) }

    fun pickLocation(option: LocationOption) {
        setLocation(InvLocation(option.node.id, option.node.name, option.path), viaScan = false)
    }

    private fun setLocation(loc: InvLocation, viaScan: Boolean) {
        val prev = _ui.value.current
        if (prev?.id == loc.id) {
            say("Already at ${loc.name}. Scan items.", MessageKind.INFO)
            return
        }
        _ui.update {
            it.copy(
                current = loc,
                picking = false,
                found = emptySet(),
                expected = expectedFor(loc, it.acceptSubLocations),
                nestOffer = if (viaScan && prev != null) NestOffer(child = loc, parent = prev) else null,
            )
        }
        log(InvEntry(keys.incrementAndGet(), loc.id, "Location: ${loc.name}", loc.path, InvOutcome.LOCATION))
        say("Now at ${loc.name}. Scan items.", MessageKind.INFO)
    }

    // ---- scanning -----------------------------------------------------------------------

    /** Called on the NFC thread. */
    fun onTag(tag: Tag) {
        val uid = NfcTags.uid(tag)
        val url = try {
            NfcTags.read(tag).url
        } catch (e: TagException) {
            app.feedback.error()
            say(e.message.orEmpty(), MessageKind.ERROR)
            return
        }
        if (url == null) {
            app.feedback.error()
            say("That tag has no URL.", MessageKind.WARNING)
            return
        }
        val parsed = homebox.parseEntityUrl(url)
        if (parsed == null) {
            app.feedback.error()
            say("Not a tag for this Homebox: $url", MessageKind.WARNING)
            return
        }
        val (isLocation, id) = parsed
        if (isLocation) {
            viewModelScope.launch(Dispatchers.IO) { mutex.withLock { scanLocationLocked(id) } }
            return
        }
        val loc = _ui.value.current
        if (loc == null) {
            app.feedback.warning()
            say("Scan a location tag first (or pick a location).", MessageKind.WARNING)
            return
        }
        if (id in _ui.value.found) {
            app.feedback.warning()
            say("Already checked ${index?.get(id)?.name ?: "that item"} here.", MessageKind.INFO)
            return
        }
        viewModelScope.launch(Dispatchers.IO) { mutex.withLock { processItemLocked(id, uid, loc) } }
    }

    private fun scanLocationLocked(id: String) {
        try {
            val idx = index
            val node = idx?.get(id)
            val loc = if (node != null) {
                InvLocation(id, node.name, idx.pathOf(id))
            } else {
                val info = homebox.entityInfo(id)
                InvLocation(id, info.name, info.name)
            }
            app.feedback.success()
            setLocation(loc, viaScan = true)
        } catch (e: HomeboxException) {
            app.feedback.error()
            say(e.message.orEmpty(), MessageKind.ERROR)
        }
    }

    private fun processItemLocked(id: String, uid: String, loc: InvLocation) {
        if (id in _ui.value.found) return
        try {
            val info = homebox.entityInfo(id)
            if (info.isLocation) {
                // A location reached through an /item/ URL; treat it like a location tag.
                scanLocationLocked(id)
                return
            }
            val today = LocalDate.now().toString()
            val accept = _ui.value.acceptSubLocations
            val where = info.locationId
            val inPlace = where == loc.id || (accept && where != null && index?.isWithin(where, loc.id) == true)

            val extras = mutableListOf<String>()
            val tagId = nfcTagId ?: homebox.ensureTag(prefs.nfcTagName).also { nfcTagId = it }
            val newTags = if (tagId !in info.tagIds) info.tagIds + tagId else null
            if (newTags != null) extras += "added ${prefs.nfcTagName}"

            var undo: UndoMove? = null
            if (!inPlace) {
                undo = UndoMove(id, info.parentId, info.locationOverrideId)
                homebox.patch(id, parentId = loc.id, tagIds = newTags)
            } else if (newTags != null) {
                homebox.patch(id, tagIds = newTags)
            }

            val fieldUpdates = mutableMapOf<String, String>()
            prefs.verifiedFieldName.ifBlank { null }?.let { name ->
                if (info.field(name) != today) fieldUpdates[name] = today
            }
            prefs.nfcFieldName.ifBlank { null }?.let { name ->
                if (info.field(name).isNullOrBlank()) {
                    fieldUpdates[name] = "$uid ($today)"
                    extras += "recorded tag ID"
                }
            }
            homebox.setTextFields(id, fieldUpdates)

            taggedIds = taggedIds + id
            _ui.update { it.copy(found = it.found + id) }
            val extraText = if (extras.isEmpty()) "" else " · " + extras.joinToString(", ")
            if (inPlace) {
                app.feedback.success()
                val here = if (where == loc.id) "here" else "in ${info.locationName}"
                log(InvEntry(keys.incrementAndGet(), id, "✓ ${info.name}", "Verified $here$extraText", InvOutcome.VERIFIED))
                say("✓ ${info.name}", MessageKind.SUCCESS)
            } else {
                app.feedback.warning()
                val from = buildString {
                    append(info.locationName ?: "no location")
                    if (info.parentId != null && info.parentId != info.locationId) append(" (inside ${info.parentName})")
                }
                log(InvEntry(keys.incrementAndGet(), id, "Moved ${info.name}", "From $from$extraText", InvOutcome.MOVED, undo))
                say("Moved ${info.name} here from $from", MessageKind.WARNING)
            }
        } catch (e: HomeboxException) {
            app.feedback.error()
            log(InvEntry(keys.incrementAndGet(), id, "Couldn't update ${index?.get(id)?.name ?: "item"}", e.message.orEmpty(), InvOutcome.ERROR))
            say(e.message.orEmpty(), MessageKind.ERROR)
        }
    }

    // ---- corrections --------------------------------------------------------------------

    fun undo(entry: InvEntry) {
        val u = entry.undo ?: return
        if (entry.undone) return
        viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                try {
                    if (u.parentId == null) throw HomeboxException("It had no location before, so there's nothing to move it back to.")
                    homebox.patch(u.entityId, parentId = u.parentId, locationId = u.locationOverrideId)
                    _ui.update { s ->
                        s.copy(
                            found = s.found - u.entityId,
                            log = s.log.map { if (it.key == entry.key) it.copy(undone = true, detail = it.detail + " · undone") else it },
                        )
                    }
                    say("Moved it back.", MessageKind.INFO)
                } catch (e: HomeboxException) {
                    say("Undo failed: ${e.message}", MessageKind.ERROR)
                }
            }
        }
    }

    /** Puts the just-scanned location inside the previous one (e.g. a box onto a shelf). */
    fun acceptNest() {
        val offer = _ui.value.nestOffer ?: return
        viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                try {
                    homebox.patch(offer.child.id, parentId = offer.parent.id)
                    loadTreeLocked()
                    val idx = index
                    val parent = offer.parent.copy(path = idx?.pathOf(offer.parent.id) ?: offer.parent.path)
                    _ui.update {
                        it.copy(
                            current = parent,
                            nestOffer = null,
                            found = emptySet(),
                            expected = expectedFor(parent, it.acceptSubLocations),
                        )
                    }
                    log(
                        InvEntry(
                            keys.incrementAndGet(), offer.child.id, "Moved ${offer.child.name}",
                            "Into ${offer.parent.name}", InvOutcome.MOVED,
                        )
                    )
                    say("Moved ${offer.child.name} into ${offer.parent.name}. Still at ${offer.parent.name}.", MessageKind.SUCCESS)
                } catch (e: HomeboxException) {
                    say("Couldn't move ${offer.child.name}: ${e.message}", MessageKind.ERROR)
                }
            }
        }
    }

    fun dismissNest() = _ui.update { it.copy(nestOffer = null) }

    private fun log(entry: InvEntry) = _ui.update { it.copy(log = (listOf(entry) + it.log).take(200)) }

    private fun say(message: String, kind: MessageKind) = _ui.update { it.copy(message = message, messageKind = kind) }
}
