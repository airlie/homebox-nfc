package io.github.homeboxnfc.homebox

import android.content.Context
import android.security.KeyChain
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager

/**
 * Presents a client certificate from the Android system credential store (the one you
 * install under Settings > Security > Encryption & credentials) during the TLS handshake.
 * This is the same mechanism the Home Assistant app uses for Cloudflare mTLS.
 *
 * [load] does blocking KeyChain calls; never call it on the main thread.
 */
class KeyChainKeyManager private constructor(
    private val alias: String,
    private val chain: Array<X509Certificate>,
    private val key: PrivateKey,
) : X509ExtendedKeyManager() {

    override fun chooseClientAlias(keyType: Array<String>?, issuers: Array<Principal>?, socket: Socket?): String = alias
    override fun chooseEngineClientAlias(keyType: Array<String>?, issuers: Array<Principal>?, engine: SSLEngine?): String = alias
    override fun getClientAliases(keyType: String?, issuers: Array<Principal>?): Array<String> = arrayOf(alias)
    override fun getServerAliases(keyType: String?, issuers: Array<Principal>?): Array<String>? = null
    override fun chooseServerAlias(keyType: String?, issuers: Array<Principal>?, socket: Socket?): String? = null
    override fun getCertificateChain(alias: String?): Array<X509Certificate> = chain
    override fun getPrivateKey(alias: String?): PrivateKey = key

    companion object {
        fun load(context: Context, alias: String): KeyChainKeyManager {
            val chain = KeyChain.getCertificateChain(context, alias)
            val key = KeyChain.getPrivateKey(context, alias)
            if (chain.isNullOrEmpty() || key == null) {
                throw HomeboxException(
                    "Client certificate \"$alias\" isn't available to this app. Choose it again in Settings.",
                    HomeboxException.Kind.NOT_CONFIGURED,
                )
            }
            return KeyChainKeyManager(alias, chain, key)
        }
    }
}
