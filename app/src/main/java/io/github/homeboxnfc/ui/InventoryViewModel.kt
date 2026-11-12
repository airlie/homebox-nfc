package io.github.homeboxnfc.ui

import android.app.Application
import android.nfc.Tag
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.homeboxnfc.HomeboxNfcApplication
import io.github.homeboxnfc.SpeakMode
import io.github.homeboxnfc.data.WorkingView
import io.github.homeboxnfc.homebox.TreeNode
import io.github.homeboxnfc.nfc.NfcTags
import io.github.homeboxnfc.nfc.TagException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong

data class InvLocation(val id: String, val name: String, val path: String)

enum class InvOutcome { VERIFIED, MOVED, LOCATION, ERROR }

/** Enough to put an entity back where it was. */
data class UndoMove(val entityId: String, val name: String, val parentId: String, val parentName: String)

data class InvEntry(
    val key: Long,
    val title: String,
    val detail: String,
    val outcome: InvOutcome,
    val undo: UndoMove? = null,
    val undone: Boolean = false,
)

/** Offered after scanning a location tag while another location was current. */
data class NestOffer(val child: InvLocation, val parent: InvLocation)

data class InventoryUi(
    val locations: List<LocationOption> = emptyList(),
    val current: InvLocation? = null,
    val picking: Boolean = false,
    val acceptSubLocations: Boolean = true,
    /** Fix tags (old address, app record) as they are scanned. */
    val fixTags: Boolean = true,
    /** NFC-tagged items the saved data says are at the current location. */
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
 *
 * Everything is decided from the saved Homebox data, so scanning is instant and works
 * offline; the resulting Homebox updates go into the outbox.
 */
class InventoryViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as HomeboxNfcApplication
    private val prefs = app.prefs
    private val store = app.store
    private val keys = AtomicLong()

    /** Set while handling a tag whose old Homebox address was just updated; added to the next message. */
    @Volatile private var updateNote = ""

    private val _ui = MutableStateFlow(InventoryUi(fixTags = prefs.inventoryFixTags))
    val ui: StateFlow<InventoryUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            store.view.collect { v ->
                _ui.update { s ->
                    s.copy(
                        locations = v?.index?.locationOptions.orEmpty(),
                        expected = expectedFor(v, s.current, s.acceptSubLocations),
                        picking = s.picking || s.current == null,
                    )
                }
            }
        }
        say(PICK_LOCATION_HINT, MessageKind.INFO)
        store.refreshIfOlderThan(60_000)
    }

    private fun expectedFor(v: WorkingView?, loc: InvLocation?, includeSub: Boolean): List<TreeNode> {
        if (v == null || loc == null) return emptyList()
        return v.index.itemsAt(loc.id, includeSub).filter { it.id in v.taggedIds }
    }

    fun setAcceptSubLocations(value: Boolean) {
        _ui.update { it.copy(acceptSubLocations = value, expected = expectedFor(store.view.value, it.current, value)) }
    }

    fun showPicker(show: Boolean) = _ui.update { it.copy(picking = show) }

    fun setFixTags(on: Boolean) {
        prefs.inventoryFixTags = on
        _ui.update { it.copy(fixTags = on) }
    }

    /** Done with this location: sum it up and clear it, ready for the next one. */
    fun finish() {
        val s = _ui.value
        val loc = s.current ?: return
        _ui.update {
            it.copy(current = null, picking = true, found = emptySet(), expected = emptyList(), log = emptyList(), nestOffer = null)
        }
        val missing = s.missing.size
        say(
            "Finished ${loc.name}: found ${s.foundExpectedCount} of ${s.expected.size} expected" +
                (if (missing > 0) ", $missing not seen." else ".") + " " + PICK_LOCATION_HINT,
            MessageKind.SUCCESS,
        )
    }

    fun pickLocation(option: LocationOption) {
        setLocation(InvLocation(option.node.id, option.node.name, option.path), viaScan = false)
    }

    private fun setLocation(loc: InvLocation, viaScan: Boolean) {
        val prev = _ui.value.current
        if (prev?.id == loc.id) {
            _ui.update { it.copy(picking = false) }
            say("Already at ${loc.name}. Scan items.", MessageKind.INFO)
            return
        }
        _ui.update {
            it.copy(
                current = loc,
                picking = false,
                found = emptySet(),
                expected = expectedFor(store.view.value, loc, it.acceptSubLocations),
                nestOffer = if (viaScan && prev != null) NestOffer(child = loc, parent = prev) else null,
            )
        }
        log(InvEntry(keys.incrementAndGet(), "Location: ${loc.name}", loc.path, InvOutcome.LOCATION))
        say("Now at ${loc.name}. Scan items.", MessageKind.INFO)
        if (viaScan) app.speaker.speak(SpeakMode.INVENTORY, loc.name)
    }

    // ---- scanning -----------------------------------------------------------------------

    /** Called on the NFC thread. */
    fun onTag(tag: Tag) {
        val uid = NfcTags.uid(tag)
        val read = try {
            readTag(app, tag, fix = prefs.inventoryFixTags)
        } catch (e: TagException) {
            app.feedback.error()
            say(e.message.orEmpty(), MessageKind.ERROR)
            return
        }
        val url = read.url
        updateNote = if (prefs.inventoryFixTags) read.note else ""
        if (url == null) {
            app.feedback.error()
            say("That tag has no URL.", MessageKind.WARNING)
            return
        }
        val parsed = app.homebox.parseEntityUrl(url)
        if (parsed == null) {
            app.feedback.error()
            say("Not a tag for this Homebox: $url", MessageKind.WARNING)
            return
        }
        val view = store.view.value
        if (view == null) {
            app.feedback.error()
            say("No saved Homebox data yet. Connect to Homebox and refresh first.", MessageKind.ERROR)
            return
        }
        val (isLocationUrl, id) = parsed
        val node = view.index[id]
        if (isLocationUrl || node?.isLocation == true) {
            app.feedback.success()
            val loc = if (node != null) {
                InvLocation(id, node.name, view.index.pathOf(id))
            } else {
                InvLocation(id, "Unknown location", "Not in the saved data. Refresh to get its name.")
            }
            setLocation(loc, viaScan = true)
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
            say("Already checked ${node?.name ?: "that item"} here.", MessageKind.INFO)
            return
        }
        processItem(view, id, node, uid, loc)
    }

    private fun processItem(view: WorkingView, id: String, node: TreeNode?, uid: String, loc: InvLocation) {
        val index = view.index
        val name = node?.name ?: "Unknown item"
        val today = LocalDate.now().toString()
        val where = node?.let { index.locationOf(id) }
        val inPlace = where != null &&
            (where == loc.id || (_ui.value.acceptSubLocations && index.isWithin(where, loc.id)))

        val tagName = prefs.nfcTagName.trim().ifBlank { null }
        val addTag = tagName?.takeIf { id !in view.taggedIds }
        val verified = prefs.verifiedFieldName.trim().ifBlank { null }
        val nfcField = prefs.nfcFieldName.trim().ifBlank { null }
        val oldParent = index.parentOf(id)

        val extras = buildList { if (addTag != null) add("added $addTag") }
        val extraText = if (extras.isEmpty()) "" else " · " + extras.joinToString(", ")
        store.enqueue(
            store.newChange(
                entityId = id,
                entityName = name,
                summary = if (inPlace) "Verified in ${loc.name}" else "Moved to ${loc.name}",
                parentId = if (inPlace) null else loc.id,
                addTag = addTag,
                setFields = verified?.let { mapOf(it to today) } ?: emptyMap(),
                setFieldsIfEmpty = nfcField?.let { mapOf(it to uid) } ?: emptyMap(),
            )
        )
        _ui.update { it.copy(found = it.found + id) }
        app.speaker.speak(SpeakMode.INVENTORY, name)

        if (inPlace) {
            app.feedback.success()
            val here = if (where == loc.id) "here" else "in ${index[where!!]?.name}"
            log(InvEntry(keys.incrementAndGet(), "✓ $name", "Verified $here$extraText", InvOutcome.VERIFIED))
            say("✓ $name", MessageKind.SUCCESS)
        } else {
            app.feedback.warning()
            val from = when {
                node == null -> "somewhere not in the saved data"
                oldParent == null -> "no location"
                else -> index.pathOf(oldParent)
            }
            val undo = oldParent?.let { UndoMove(id, name, it, index[it]?.name ?: "previous place") }
            log(InvEntry(keys.incrementAndGet(), "Moved $name", "From $from$extraText", InvOutcome.MOVED, undo))
            say("Moved $name here from $from", MessageKind.WARNING)
        }
    }

    // ---- corrections --------------------------------------------------------------------

    /** Queues a move back. Changes are sent in order, so this works whether or not the move has synced. */
    fun undo(entry: InvEntry) {
        val u = entry.undo ?: return
        if (entry.undone) return
        store.enqueue(
            store.newChange(u.entityId, u.name, "Moved back to ${u.parentName} (undo)", parentId = u.parentId)
        )
        _ui.update { s ->
            s.copy(
                found = s.found - u.entityId,
                log = s.log.map { if (it.key == entry.key) it.copy(undone = true, detail = it.detail + " · undone") else it },
            )
        }
        say("Moved ${u.name} back to ${u.parentName}.", MessageKind.INFO)
    }

    /** Puts the just-scanned location inside the previous one (e.g. a box onto a shelf). */
    fun acceptNest() {
        val offer = _ui.value.nestOffer ?: return
        store.enqueue(
            store.newChange(offer.child.id, offer.child.name, "Moved into ${offer.parent.name}", parentId = offer.parent.id)
        )
        val parent = offer.parent.copy(path = store.view.value?.index?.pathOf(offer.parent.id) ?: offer.parent.path)
        _ui.update {
            it.copy(
                current = parent,
                nestOffer = null,
                found = emptySet(),
                expected = expectedFor(store.view.value, parent, it.acceptSubLocations),
            )
        }
        log(InvEntry(keys.incrementAndGet(), "Moved ${offer.child.name}", "Into ${offer.parent.name}", InvOutcome.MOVED))
        say("Moved ${offer.child.name} into ${offer.parent.name}. Still at ${offer.parent.name}.", MessageKind.SUCCESS)
    }

    fun dismissNest() = _ui.update { it.copy(nestOffer = null) }

    private fun log(entry: InvEntry) = _ui.update { it.copy(log = (listOf(entry) + it.log).take(200)) }

    private fun say(message: String, kind: MessageKind) {
        val note = updateNote
        updateNote = ""
        _ui.update { it.copy(message = message + note, messageKind = kind) }
    }
}
