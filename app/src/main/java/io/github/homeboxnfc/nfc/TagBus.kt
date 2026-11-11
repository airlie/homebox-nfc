package io.github.homeboxnfc.nfc

import android.nfc.Tag

/**
 * MainActivity keeps NFC reader mode on while the app is in front and hands every tag
 * to whichever screen registered a handler. Handlers run on the NFC binder thread, so
 * they can do blocking tag I/O directly.
 */
object TagBus {
    @Volatile
    var handler: ((Tag) -> Unit)? = null

    fun dispatch(tag: Tag) {
        handler?.invoke(tag)
    }
}
