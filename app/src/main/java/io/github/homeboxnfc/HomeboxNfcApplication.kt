package io.github.homeboxnfc

import android.app.Application
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
    lateinit var feedback: Feedback
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        secrets = SecretStore(this)
        homebox = HomeboxClient(this, prefs, secrets)
        feedback = Feedback(this)
    }
}
