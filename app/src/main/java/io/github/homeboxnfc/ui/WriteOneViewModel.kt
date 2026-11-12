package io.github.homeboxnfc.ui

import android.app.Application
import android.nfc.Tag
import androidx.lifecycle.AndroidViewModel
import io.github.homeboxnfc.HomeboxNfcApplication
import io.github.homeboxnfc.nfc.TagException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class EntryOption(val id: String, val name: String, val path: String, val isLocation: Boolean, val tagged: Boolean)

data class WriteOneUi(
    val query: String = "",
    val selected: EntryOption? = null,
    val message: String? = "Search for an item or location, pick it, then tap a tag.",
    val messageKind: MessageKind = MessageKind.INFO,
)

/** Write a single entry's tag: search, pick, tap. */
class WriteOneViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as HomeboxNfcApplication
    private val writer = TagWriter(app)

    private val _ui = MutableStateFlow(WriteOneUi())
    val ui: StateFlow<WriteOneUi> = _ui.asStateFlow()

    init {
        app.store.refreshIfOlderThan(60_000)
    }

    fun setQuery(q: String) = _ui.update { it.copy(query = q) }

    /** Entries matching the query, untagged first. */
    fun search(query: String): List<EntryOption> {
        val view = app.store.view.value ?: return emptyList()
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return view.index.all.asSequence()
            .filter { it.name.contains(q, ignoreCase = true) }
            .map { EntryOption(it.id, it.name, view.index.containerPath(it.id), it.isLocation, it.id in view.taggedIds) }
            .sortedWith(compareBy<EntryOption>({ it.tagged }, { !it.name.startsWith(q, ignoreCase = true) }, { it.name.lowercase() }))
            .take(100)
            .toList()
    }

    fun select(option: EntryOption?) {
        writer.forgetLast()
        _ui.update {
            it.copy(
                selected = option,
                message = option?.let { o -> "Tap a tag for ${o.name}" } ?: "Search for an item or location, pick it, then tap a tag.",
                messageKind = MessageKind.INFO,
            )
        }
    }

    /** Called on the NFC thread. With nothing picked, a tap just says what the tag is for. */
    fun onTag(tag: Tag) {
        val entry = _ui.value.selected
        if (entry == null) {
            identify(tag)
            return
        }
        when (val r = writer.write(tag, entry.id, entry.isLocation, entry.name)) {
            is TagWriter.Result.SameAsLast -> {
                app.feedback.warning()
                say("That tag is already done. Pick another entry to write.", MessageKind.WARNING)
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
                writer.queueMark(entry.id, entry.name, r.uid)
                _ui.update {
                    it.copy(
                        selected = null,
                        message = if (r.alreadyThere) "✓ That tag already had ${entry.name}. Homebox marked." else "✓ Wrote ${entry.name}.",
                        messageKind = MessageKind.SUCCESS,
                    )
                }
            }
        }
    }

    private fun identify(tag: Tag) {
        val read = try {
            readTag(app, tag, fix = true)
        } catch (e: TagException) {
            say(e.message.orEmpty(), MessageKind.ERROR)
            return
        }
        val url = read.url
        val id = url?.let { app.homebox.parseEntityUrl(it)?.second }
        val name = id?.let { app.store.view.value?.index?.get(it)?.name }
        say(
            when {
                url == null -> "That tag is empty. Pick an entry first to write it."
                name != null -> "That tag is for $name. Pick an entry first to write a tag."
                else -> "That tag holds $url. Pick an entry first to write it."
            } + read.note,
            MessageKind.INFO,
        )
    }

    private fun say(message: String, kind: MessageKind) = _ui.update { it.copy(message = message, messageKind = kind) }
}
