package io.github.homeboxnfc

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import io.github.homeboxnfc.data.HomeboxStore
import io.github.homeboxnfc.data.Prefs
import io.github.homeboxnfc.data.SecretStore
import io.github.homeboxnfc.homebox.HomeboxClient

class HomeboxNfcApplication : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var secrets: SecretStore
        private set
    lateinit var homebox: HomeboxClient
        private set
    lateinit var store: HomeboxStore
        private set
    lateinit var feedback: Feedback
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        secrets = SecretStore(this)
        homebox = HomeboxClient(this, prefs, secrets)
        store = HomeboxStore(this, prefs, homebox)
        feedback = Feedback(this)

        // While the app is running, send queued changes as soon as a network shows up.
        // (When it isn't running, SyncWorker takes care of it.)
        getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (store.hasPending) store.syncSoon()
                }
            }
        )
    }
}
