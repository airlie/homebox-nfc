package io.github.homeboxnfc.ui

import android.app.Application
import android.nfc.Tag
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.homeboxnfc.HomeboxNfcApplication
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
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

enum class PhotoState { NONE, TAKING, UPLOADING, DONE, FAILED }

data class AddedItem(
    val key: Long,
    val id: String,
    val name: String,
    val locationName: String,
    val photo: PhotoState = PhotoState.NONE,
    val photoError: String? = null,
)

/** A photo the screen should take for [itemKey], saved to [file]. */
data class PhotoRequest(val itemKey: Long, val file: File)

data class AddUi(
    val locations: List<LocationOption> = emptyList(),
    val location: InvLocation? = null,
    val picking: Boolean = true,
    /** Name for the next item. Blank = automatic ("Item 3f9c"). */
    val name: String = "",
    val photo: Boolean = false,
    val added: List<AddedItem> = emptyList(),
    val photoRequest: PhotoRequest? = null,
    val message: String? = null,
    val messageKind: MessageKind = MessageKind.INFO,
)

/**
 * Add: tap a blank tag to create a new Homebox item in the chosen location, write the tag
 * for it, and optionally take its photo. Tap one tag to add one item, or keep tapping for a batch.
 *
 * Unlike the other modes this needs a connection: Homebox assigns the new item's ID, and the
 * tag can't be written without it.
 */
class AddViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as HomeboxNfcApplication
    private val prefs = app.prefs
    private val store = app.store
    private val writer = TagWriter(app)
    private val keys = AtomicLong()

    /** Created in Homebox, but its tag write failed: the next tap writes this instead of creating another. */
    @Volatile private var unwritten: Pair<String, String>? = null
    @Volatile private var armedOverwriteUid: String? = null

    private val _ui = MutableStateFlow(AddUi(photo = prefs.addPhoto))
    val ui: StateFlow<AddUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            store.view.collect { v -> _ui.update { it.copy(locations = v?.index?.locationOptions.orEmpty()) } }
        }
        say("Pick where the new items are, or scan that location's tag.", MessageKind.INFO)
        store.refreshIfOlderThan(60_000)
    }

    fun showPicker(show: Boolean) = _ui.update { it.copy(picking = show || it.location == null) }

    fun pickLocation(option: LocationOption) = setLocation(InvLocation(option.node.id, option.node.name, option.path))

    private fun setLocation(loc: InvLocation) {
        _ui.update { it.copy(location = loc, picking = false) }
        say("Adding to ${loc.name}. Tap a blank tag to add an item.", MessageKind.INFO)
    }

    fun setName(name: String) = _ui.update { it.copy(name = name) }

    fun setPhoto(on: Boolean) {
        prefs.addPhoto = on
        _ui.update { it.copy(photo = on) }
    }

    /** Called on the NFC thread. */
    fun onTag(tag: Tag) {
        val uid = NfcTags.uid(tag)
        val snap = try {
            NfcTags.read(tag)
        } catch (e: TagException) {
            app.feedback.error()
            say(e.message.orEmpty(), MessageKind.ERROR)
            return
        }
        val view = store.view.value
        val parsed = snap.url?.let { app.homebox.parseEntityUrl(it) }
        if (parsed != null) {
            val (isLocationUrl, id) = parsed
            val node = view?.index?.get(id)
            if (isLocationUrl || node?.isLocation == true) {
                app.feedback.success()
                setLocation(
                    InvLocation(id, node?.name ?: "Unknown location", node?.let { view.index.pathOf(id) } ?: "Not in the saved data")
                )
            } else {
                app.feedback.warning()
                say("That tag is already for ${node?.name ?: "a Homebox item"}. Use a blank tag.", MessageKind.WARNING)
            }
            return
        }

        val loc = _ui.value.location
        if (loc == null) {
            app.feedback.warning()
            say("Pick a location first, or scan a location's tag.", MessageKind.WARNING)
            return
        }
        if (!snap.isEmpty && uid != armedOverwriteUid) {
            armedOverwriteUid = uid
            app.feedback.warning()
            say("This tag isn't blank (${snap.url ?: "${snap.recordCount} record(s)"}). Tap it again to use it anyway.", MessageKind.WARNING)
            return
        }
        armedOverwriteUid = null

        // Create the item, unless the last one is still waiting for its tag.
        val (id, name) = unwritten ?: run {
            val name = _ui.value.name.trim().ifBlank { autoName(uid, view?.index) }
            val id = try {
                app.homebox.createItem(name, loc.id)
            } catch (e: HomeboxException) {
                app.feedback.error()
                say(
                    if (e.kind == HomeboxException.Kind.NETWORK) "Adding needs a connection to Homebox. ${e.message}"
                    else "Couldn't add the item: ${e.message}",
                    MessageKind.ERROR,
                )
                return
            }
            (id to name).also { unwritten = it }
        }

        when (val r = writer.write(tag, id, isLocation = false, name = name, force = true)) {
            is TagWriter.Result.Written -> {
                unwritten = null
                writer.queueMark(id, name, r.uid, parentId = loc.id)
                app.feedback.success()
                val key = keys.incrementAndGet()
                val takePhoto = _ui.value.photo
                val item = AddedItem(key, id, name, loc.name, if (takePhoto) PhotoState.TAKING else PhotoState.NONE)
                _ui.update {
                    it.copy(
                        name = "",
                        added = (listOf(item) + it.added).take(100),
                        photoRequest = if (takePhoto) PhotoRequest(key, photoFile(id)) else null,
                    )
                }
                say("✓ Added $name to ${loc.name}." + if (takePhoto) " Take its photo." else " Tap another blank tag to add more.", MessageKind.SUCCESS)
            }
            is TagWriter.Result.SameAsLast -> {
                app.feedback.warning()
                say("That's the tag you just wrote. Use a new blank tag.", MessageKind.WARNING)
            }
            is TagWriter.Result.ConfirmOverwrite -> {
                app.feedback.warning()
                say(r.message, MessageKind.WARNING)
            }
            is TagWriter.Result.Failed -> {
                app.feedback.error()
                say("Added $name to Homebox, but writing the tag failed: ${r.message} Tap a tag again to finish.", MessageKind.ERROR)
            }
        }
    }

    // ---- photos ---------------------------------------------------------------------------

    /** The request the camera is open for (kept here so it survives the screen being rebuilt). */
    @Volatile private var awaiting: PhotoRequest? = null

    /** The screen has started the camera for [request]. */
    fun photoStarted(request: PhotoRequest) {
        awaiting = request
        _ui.update { it.copy(photoRequest = null) }
    }

    /** Camera result. */
    fun onPhotoResult(taken: Boolean) {
        val request = awaiting ?: return
        awaiting = null
        onPhotoTaken(request, taken)
    }

    private fun onPhotoTaken(request: PhotoRequest, taken: Boolean) {
        if (!taken || !request.file.exists() || request.file.length() == 0L) {
            request.file.delete()
            setPhoto(request.itemKey, PhotoState.NONE, null)
            say("No photo taken. Tap another blank tag to add more.", MessageKind.INFO)
            return
        }
        upload(request)
    }

    fun retryPhoto(item: AddedItem) {
        val file = photoFile(item.id)
        if (file.exists()) upload(PhotoRequest(item.key, file)) else retakePhoto(item)
    }

    fun retakePhoto(item: AddedItem) {
        setPhoto(item.key, PhotoState.TAKING, null)
        _ui.update { it.copy(photoRequest = PhotoRequest(item.key, photoFile(item.id))) }
    }

    private fun upload(request: PhotoRequest) {
        val item = _ui.value.added.firstOrNull { it.key == request.itemKey } ?: return
        setPhoto(item.key, PhotoState.UPLOADING, null)
        viewModelScope.launch {
            val error = try {
                withContext(Dispatchers.IO) { app.homebox.uploadPhoto(item.id, request.file) }
                null
            } catch (e: HomeboxException) {
                e.message
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            if (error == null) {
                request.file.delete()
                setPhoto(item.key, PhotoState.DONE, null)
                say("✓ Photo added to ${item.name}. Tap another blank tag to add more.", MessageKind.SUCCESS)
            } else {
                setPhoto(item.key, PhotoState.FAILED, error)
                say("Couldn't upload the photo for ${item.name}: $error", MessageKind.ERROR)
            }
        }
    }

    private fun setPhoto(key: Long, state: PhotoState, error: String?) = _ui.update { s ->
        s.copy(added = s.added.map { if (it.key == key) it.copy(photo = state, photoError = error) else it })
    }

    private fun photoFile(id: String) = File(File(app.cacheDir, "photos").apply { mkdirs() }, "$id.jpg")

    private fun say(message: String, kind: MessageKind) = _ui.update { it.copy(message = message, messageKind = kind) }

    companion object {
        /**
         * "Item 3f9c": four hex characters from a hash of the tag's ID, so the same tag always
         * suggests the same name. Longer if that name is already taken.
         */
        fun autoName(uid: String, index: TreeIndex?): String {
            val hex = MessageDigest.getInstance("SHA-256").digest(uid.toByteArray()).joinToString("") { "%02x".format(it) }
            val taken = index?.all?.map { it.name.lowercase() }?.toSet().orEmpty()
            for (len in 4..hex.length step 2) {
                val name = "Item ${hex.take(len)}"
                if (name.lowercase() !in taken) return name
            }
            return "Item $hex"
        }
    }
}
