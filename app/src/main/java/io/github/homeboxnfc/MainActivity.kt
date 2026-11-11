package io.github.homeboxnfc

import android.nfc.NfcAdapter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.homeboxnfc.nfc.TagBus
import io.github.homeboxnfc.ui.App

class MainActivity : ComponentActivity() {
    private var nfc: NfcAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        nfc = NfcAdapter.getDefaultAdapter(this)
        setContent { App(activity = this, nfcAdapter = nfc) }
    }

    override fun onResume() {
        super.onResume()
        val store = (application as HomeboxNfcApplication).store
        if (store.hasPending) store.syncSoon()
        // Reader mode: while the app is in front, tags come straight to us instead of
        // the system opening them (which would launch Chrome mid-batch).
        val extras = Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250) }
        nfc?.enableReaderMode(
            this,
            { tag -> TagBus.dispatch(tag) },
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V,
            extras,
        )
    }

    override fun onPause() {
        super.onPause()
        nfc?.disableReaderMode(this)
    }
}
