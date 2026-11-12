package io.github.homeboxnfc.ui

import android.app.Application
import android.nfc.Tag
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.homeboxnfc.HomeboxNfcApplication
import io.github.homeboxnfc.SpeakMode
import io.github.homeboxnfc.homebox.TreeNode
import io.github.homeboxnfc.nfc.TagException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class QueueItem(val id: String, val name: String, val path: String, val isLocation: Boolean)

/** A batch run, persisted after every step so it survives the app being closed. */
data class BatchSession(
    val rootId: String,
    val rootName: String,
    val recursive: Boolean,
    val includeLocations: Boolean,
    val items: List<QueueItem>,
    val index: Int,
    /** IDs written during this session (also guards against re-queueing before Homebox catches up). */
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

data class BatchUi(
    val session: BatchSession? = null,
    /** Location picked (or scanned) on the setup screen. */
    val selectedRootId: String? = null,
    val message: String? = null,
    val messageKind: MessageKind = MessageKind.INFO,
)

/**
 * Batch write. Works entirely from the saved Homebox data, so it keeps going without a
 * connection; Homebox updates are queued in the outbox.
 */
class BatchViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as HomeboxNfcApplication
    private val prefs = app.prefs
    private val store = app.store
    private val writer = TagWriter(app)

    private val _ui = MutableStateFlow(BatchUi(session = BatchSession.fromJson(prefs.batchSessionJson)))
    val ui: StateFlow<BatchUi> = _ui.asStateFlow()

    init {
        _ui.value.session?.current?.let {
            say("Resuming. Tap a tag for: ${it.name}", MessageKind.INFO)
            app.speaker.speak(SpeakMode.BATCH, it.name)
        }
        store.refreshIfOlderThan(60_000)
    }

    fun select(id: String?) = _ui.update { it.copy(selectedRootId = id) }

    fun start(option: LocationOption, recursive: Boolean, includeLocations: Boolean) {
        buildSession(option.node.id, option.path, recursive, includeLocations, done = emptySet())
    }

    /** Fetches fresh data from Homebox (if reachable) and rebuilds the list, dropping anything tagged since. */
    fun refresh() {
        val s = _ui.value.session ?: return
        viewModelScope.launch {
            val error = store.refresh()
            if (error != null) say("Couldn't refresh, using saved data. $error", MessageKind.WARNING)
            buildSession(s.rootId, s.rootName, s.recursive, s.includeLocations, s.done, quiet = error != null)
        }
    }

    private fun buildSession(
        rootId: String, rootName: String, recursive: Boolean, includeLocations: Boolean,
        done: Set<String>, quiet: Boolean = false,
    ) {
        val view = store.view.value
        if (view == null) {
            say("No saved Homebox data yet. Connect to Homebox and tap Refresh.", MessageKind.ERROR)
            return
        }
        val root = view.index[rootId]
        if (root == null) {
            say("That location isn't in the saved data any more.", MessageKind.ERROR)
            return
        }
        val tagged = view.taggedIds
        val queue = mutableListOf<QueueItem>()
        fun collect(nodes: List<TreeNode>, path: String) {
            for (n in nodes) {
                val wanted = !n.isLocation || includeLocations
                if (wanted && n.id !in tagged && n.id !in done) queue += QueueItem(n.id, n.name, path, n.isLocation)
                if (recursive) collect(n.children, "$path › ${n.name}")
            }
        }
        collect(root.children, rootName)
        queue.sortWith(compareBy(NaturalOrder) { it.name })

        val session = BatchSession(rootId, rootName, recursive, includeLocations, queue, 0, done)
        save(session)
        _ui.update { it.copy(session = session) }
        if (quiet) return
        when {
            queue.isEmpty() -> say("Nothing left to tag in $rootName.", MessageKind.SUCCESS)
            else -> say("${queue.size} to tag. Tap a tag for: ${queue.first().name}", MessageKind.INFO)
        }
        announce(session.current)
    }

    /** Called on the NFC thread while the tag is in the field. */
    fun onTag(tag: Tag) {
        val session = _ui.value.session ?: return selectByTag(tag)
        val item = session.current ?: return
        when (val r = writer.write(tag, item.id, item.isLocation, item.name)) {
            is TagWriter.Result.SameAsLast -> {
                app.feedback.warning()
                say("That's the tag you just wrote. Use a new tag for: ${item.name}", MessageKind.WARNING)
            }
            is TagWriter.Result.ConfirmOverwrite -> {
                app.feedback.warning()
                say(r.message, MessageKind.WARNING)
            }
            is TagWriter.Result.Failed -> {
                app.feedback.error()
                say(r.message, MessageKind.ERROR)
            }
            is TagWriter.Result.Written -> {
                app.feedback.success()
                writer.queueMark(item.id, item.name, r.uid)
                advance(item, "✓ ${item.name}")
            }
        }
    }

    /** Before a run starts: a location's tag picks that location. */
    private fun selectByTag(tag: Tag) {
        val read = try {
            readTag(app, tag, fix = true)
        } catch (e: TagException) {
            app.feedback.error()
            say(e.message.orEmpty(), MessageKind.ERROR)
            return
        }
        val url = read.url
        val id = url?.let { app.homebox.parseEntityUrl(it)?.second }
        val node = id?.let { store.view.value?.index?.get(it) }
        when {
            url == null -> {
                app.feedback.warning()
                say("That tag is empty. Scan a location's tag to pick it.", MessageKind.WARNING)
            }
            node == null -> {
                app.feedback.warning()
                say("That tag isn't for a location in the saved Homebox data." + read.note, MessageKind.WARNING)
            }
            !node.isLocation -> {
                app.feedback.warning()
                say("That's the tag for ${node.name}, an item. Scan a location's tag to pick it." + read.note, MessageKind.WARNING)
            }
            else -> {
                app.feedback.success()
                _ui.update { it.copy(selectedRootId = node.id) }
                say("Picked ${node.name}. Tap Start when ready." + read.note, MessageKind.SUCCESS)
                app.speaker.speak(SpeakMode.BATCH, node.name)
            }
        }
    }

    private fun advance(item: QueueItem, done: String) {
        val s = _ui.value.session ?: return
        val next = s.copy(index = s.index + 1, done = s.done + item.id)
        save(next)
        _ui.update { it.copy(session = next) }
        val upNext = next.current
        say(if (upNext == null) "$done. All done!" else "$done. Next: ${upNext.name}", MessageKind.SUCCESS)
        announce(upNext)
    }

    /** Reads the next entry's name aloud (if turned on), so you can find it without looking. */
    private fun announce(item: QueueItem?) {
        if (item == null) app.speaker.speakPhrase(SpeakMode.BATCH, "All done") else app.speaker.speak(SpeakMode.BATCH, item.name)
    }

    fun skip() {
        val s = _ui.value.session ?: return
        if (s.current == null) return
        val next = s.copy(index = s.index + 1)
        save(next)
        _ui.update { it.copy(session = next) }
        say("Skipped ${s.current?.name}." + (next.current?.let { " Next: ${it.name}" } ?: ""), MessageKind.INFO)
        announce(next.current)
    }

    /** Go back one entry, e.g. to rewrite a tag. Allows the just-written tag to be reused. */
    fun back() {
        val s = _ui.value.session ?: return
        if (s.index == 0) return
        val prev = s.copy(index = s.index - 1)
        writer.forgetLast()
        save(prev)
        _ui.update { it.copy(session = prev) }
        say("Back to: ${prev.current?.name}", MessageKind.INFO)
        announce(prev.current)
    }

    fun endSession() {
        prefs.batchSessionJson = ""
        writer.forgetLast()
        _ui.update { BatchUi(selectedRootId = it.selectedRootId) }
    }

    private fun save(s: BatchSession) {
        prefs.batchSessionJson = s.toJson()
    }

    private fun say(message: String, kind: MessageKind) {
        _ui.update { it.copy(message = message, messageKind = kind) }
    }
}
