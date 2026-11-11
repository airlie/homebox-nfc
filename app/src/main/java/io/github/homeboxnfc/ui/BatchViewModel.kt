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
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

data class QueueItem(val id: String, val name: String, val path: String, val isLocation: Boolean)

/** A batch run, persisted after every step so it survives the app being closed. */
data class BatchSession(
    val rootId: String,
    val rootName: String,
    val recursive: Boolean,
    val includeLocations: Boolean,
    val items: List<QueueItem>,
    val index: Int,
    /** IDs written during this session (also guards against re-queueing if Homebox lags). */
    val done: Set<String>,
) {
    val current: QueueItem? get() = items.getOrNull(index)

    fun toJson(): String = JSONObject()
        .put("rootId", rootId)
        .put("rootName", rootName)
        .put("recursive", recursive)
        .put("includeLocations", includeLocations)
        .put("index", index)
        .put("done", JSONArray(done.toList()))
        .put("items", JSONArray(items.map {
            JSONObject().put("id", it.id).put("name", it.name).put("path", it.path).put("isLocation", it.isLocation)
        }))
        .toString()

    companion object {
        fun fromJson(s: String): BatchSession? = try {
            val o = JSONObject(s)
            val items = o.getJSONArray("items")
            val done = o.optJSONArray("done") ?: JSONArray()
            BatchSession(
                rootId = o.getString("rootId"),
                rootName = o.optString("rootName"),
                recursive = o.optBoolean("recursive"),
                includeLocations = o.optBoolean("includeLocations"),
                items = (0 until items.length()).map { i ->
                    val q = items.getJSONObject(i)
                    QueueItem(q.getString("id"), q.optString("name"), q.optString("path"), q.optBoolean("isLocation"))
                },
                index = o.optInt("index"),
                done = (0 until done.length()).map { done.getString(it) }.toSet(),
            )
        } catch (e: Exception) {
            null
        }
    }
}

enum class MessageKind { INFO, SUCCESS, WARNING, ERROR }

/** The tag was written but Homebox couldn't be updated; the user decides what to do. */
data class PendingMark(val item: QueueItem, val uid: String)

data class BatchUi(
    val loading: Boolean = false,
    val locations: List<LocationOption> = emptyList(),
    val session: BatchSession? = null,
    val message: String? = null,
    val messageKind: MessageKind = MessageKind.INFO,
    val busy: Boolean = false,
    val pendingMark: PendingMark? = null,
)

class BatchViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as HomeboxNfcApplication
    private val prefs = app.prefs
    private val homebox = app.homebox

    private val _ui = MutableStateFlow(BatchUi(session = BatchSession.fromJson(prefs.batchSessionJson)))
    val ui: StateFlow<BatchUi> = _ui.asStateFlow()

    @Volatile private var lastWrittenUid: String? = null
    @Volatile private var armedOverwriteUid: String? = null
    @Volatile private var nfcTagId: String? = null

    init {
        _ui.value.session?.current?.let { say("Resuming. Tap a tag for: ${it.name}", MessageKind.INFO) }
    }

    // ---- setup --------------------------------------------------------------------------

    fun loadLocations() {
        _ui.update { it.copy(loading = true, message = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val options = TreeIndex(homebox.tree()).locationOptions
                _ui.update { it.copy(loading = false, locations = options) }
                if (options.isEmpty()) say("No locations found in Homebox.", MessageKind.WARNING)
            } catch (e: HomeboxException) {
                _ui.update { it.copy(loading = false) }
                say(e.message.orEmpty(), MessageKind.ERROR)
            }
        }
    }

    fun start(option: LocationOption, recursive: Boolean, includeLocations: Boolean) {
        buildSession(option.node.id, option.path, recursive, includeLocations, done = emptySet(), tree = option.node)
    }

    /** Re-fetches from Homebox, dropping anything tagged since. */
    fun refresh() {
        val s = _ui.value.session ?: return
        buildSession(s.rootId, s.rootName, s.recursive, s.includeLocations, s.done, tree = null)
    }

    private fun buildSession(
        rootId: String, rootName: String, recursive: Boolean, includeLocations: Boolean,
        done: Set<String>, tree: TreeNode?,
    ) {
        _ui.update { it.copy(loading = true, message = null, pendingMark = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val root = tree ?: findNode(homebox.tree(), rootId)
                    ?: throw HomeboxException("That location no longer exists in Homebox.")
                val tagId = homebox.findTag(prefs.nfcTagName).also { nfcTagId = it }
                val alreadyTagged = tagId?.let { homebox.entityIdsWithTag(it) } ?: emptySet()

                val queue = mutableListOf<QueueItem>()
                fun collect(nodes: List<TreeNode>, path: String) {
                    for (n in nodes) {
                        val wanted = !n.isLocation || includeLocations
                        if (wanted && n.id !in alreadyTagged && n.id !in done) {
                            queue += QueueItem(n.id, n.name, path, n.isLocation)
                        }
                        if (recursive) collect(n.children, "$path › ${n.name}")
                    }
                }
                collect(root.children, rootName)

                val session = BatchSession(rootId, rootName, recursive, includeLocations, queue, 0, done)
                save(session)
                _ui.update { it.copy(loading = false, session = session) }
                val skipped = if (alreadyTagged.isNotEmpty()) " (already-tagged entries skipped)" else ""
                if (queue.isEmpty()) {
                    say("Nothing left to tag in $rootName$skipped.", MessageKind.SUCCESS)
                } else {
                    say("${queue.size} to tag$skipped. Tap a tag for: ${queue.first().name}", MessageKind.INFO)
                }
            } catch (e: HomeboxException) {
                _ui.update { it.copy(loading = false) }
                say(e.message.orEmpty(), MessageKind.ERROR)
            }
        }
    }

    private fun findNode(nodes: List<TreeNode>, id: String): TreeNode? {
        for (n in nodes) {
            if (n.id == id) return n
            findNode(n.children, id)?.let { return it }
        }
        return null
    }

    // ---- tag handling -------------------------------------------------------------------

    /** Called on the NFC thread while the tag is in the field. */
    fun onTag(tag: Tag) {
        val state = _ui.value
        val session = state.session ?: return
        val item = session.current ?: return
        if (state.busy || state.loading) return
        if (state.pendingMark != null) {
            app.feedback.warning()
            say("Sort out the Homebox update for ${state.pendingMark.item.name} first (Retry or Continue).", MessageKind.WARNING)
            return
        }

        val uid = NfcTags.uid(tag)
        if (uid == lastWrittenUid) {
            app.feedback.warning()
            say("That's the tag you just wrote. Use a new tag for: ${item.name}", MessageKind.WARNING)
            return
        }

        val url = homebox.entityUrl(item.id, item.isLocation)
        val pkg = prefs.aarPackage.trim().ifBlank { null }
        try {
            val snap = NfcTags.read(tag)
            val alreadyThis = snap.url == url && (pkg == null || snap.hasAar(pkg))
            val existing = snap.url
            if (!alreadyThis && existing != null && uid != armedOverwriteUid) {
                armedOverwriteUid = uid
                app.feedback.warning()
                val what = if (homebox.isHomeboxUrl(existing)) "a different Homebox entry" else existing
                say("This tag already holds $what. Tap it again to overwrite.", MessageKind.WARNING)
                return
            }
            if (!alreadyThis) NfcTags.write(tag, NfcTags.buildMessage(url, pkg))
        } catch (e: TagException) {
            app.feedback.error()
            say(e.message.orEmpty(), MessageKind.ERROR)
            return
        }

        armedOverwriteUid = null
        lastWrittenUid = uid
        app.feedback.success()
        _ui.update { it.copy(busy = true, message = "Wrote ${item.name}. Updating Homebox…", messageKind = MessageKind.INFO) }
        mark(item, uid)
    }

    private fun mark(item: QueueItem, uid: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val tagId = nfcTagId ?: homebox.ensureTag(prefs.nfcTagName).also { nfcTagId = it }
                homebox.markTagged(item.id, tagId, prefs.nfcFieldName.ifBlank { null }, "$uid (${LocalDate.now()})")
                advance(item, "✓ ${item.name}")
            } catch (e: HomeboxException) {
                app.feedback.error()
                _ui.update {
                    it.copy(
                        busy = false,
                        pendingMark = PendingMark(item, uid),
                        message = "Tag written, but updating Homebox failed: ${e.message}",
                        messageKind = MessageKind.ERROR,
                    )
                }
            }
        }
    }

    fun retryMark() {
        val pm = _ui.value.pendingMark ?: return
        _ui.update { it.copy(pendingMark = null, busy = true, message = "Retrying Homebox update…", messageKind = MessageKind.INFO) }
        mark(pm.item, pm.uid)
    }

    /** Keep going without marking the item in Homebox (the tag itself is fine). */
    fun continueWithoutMark() {
        val pm = _ui.value.pendingMark ?: return
        advance(pm.item, "Tag written for ${pm.item.name}; Homebox not updated")
    }

    private fun advance(item: QueueItem, done: String) {
        val s = _ui.value.session ?: return
        val next = s.copy(index = s.index + 1, done = s.done + item.id)
        save(next)
        _ui.update { it.copy(session = next, busy = false, pendingMark = null) }
        val upNext = next.current
        if (upNext == null) {
            say("$done. All done!", MessageKind.SUCCESS)
        } else {
            say("$done. Next: ${upNext.name}", MessageKind.SUCCESS)
        }
    }

    fun skip() {
        val s = _ui.value.session ?: return
        if (s.current == null || _ui.value.busy) return
        val next = s.copy(index = s.index + 1)
        save(next)
        _ui.update { it.copy(session = next, pendingMark = null) }
        say("Skipped ${s.current?.name}." + (next.current?.let { " Next: ${it.name}" } ?: ""), MessageKind.INFO)
    }

    /** Go back one entry, e.g. to rewrite a tag. Allows the just-written tag to be reused. */
    fun back() {
        val s = _ui.value.session ?: return
        if (s.index == 0 || _ui.value.busy) return
        val prev = s.copy(index = s.index - 1)
        lastWrittenUid = null
        save(prev)
        _ui.update { it.copy(session = prev, pendingMark = null) }
        say("Back to: ${prev.current?.name}", MessageKind.INFO)
    }

    fun endSession() {
        prefs.batchSessionJson = ""
        lastWrittenUid = null
        armedOverwriteUid = null
        _ui.update { BatchUi(locations = it.locations) }
    }

    private fun save(s: BatchSession) {
        prefs.batchSessionJson = s.toJson()
    }

    private fun say(message: String, kind: MessageKind) {
        _ui.update { it.copy(message = message, messageKind = kind) }
    }
}
