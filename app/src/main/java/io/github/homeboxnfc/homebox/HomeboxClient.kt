package io.github.homeboxnfc.homebox

import android.content.Context
import android.os.Build
import io.github.homeboxnfc.data.AccessMode
import io.github.homeboxnfc.data.Prefs
import io.github.homeboxnfc.data.SecretStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class HomeboxException(message: String, val kind: Kind = Kind.OTHER) : Exception(message) {
    enum class Kind { NOT_CONFIGURED, NEEDS_LOGIN, ACCESS_BLOCKED, OTHER }
}

/** A node from /entities/tree. [type] is "location" or "item". */
data class TreeNode(
    val id: String,
    val name: String,
    val type: String,
    val children: List<TreeNode>,
) {
    val isLocation: Boolean get() = type == "location"
}

/**
 * Minimal client for the Homebox v0.27+ REST API (the "entities" API, where items and
 * locations are both entities and labels are called tags).
 *
 * Every function does blocking network I/O; call from a background dispatcher.
 */
class HomeboxClient(
    private val context: Context,
    private val prefs: Prefs,
    private val secrets: SecretStore,
) {
    private val json = "application/json".toMediaType() // Homebox matches this exactly; no charset suffix
    private var cachedHttp: Pair<String, OkHttpClient>? = null

    val baseUrl: String get() = prefs.serverUrl.trim().trimEnd('/')

    fun entityUrl(id: String, isLocation: Boolean): String =
        "$baseUrl/${if (isLocation) "location" else "item"}/$id"

    /** True if [url] points at an item or location page on this Homebox server. */
    fun isHomeboxUrl(url: String): Boolean =
        baseUrl.isNotBlank() && (url.startsWith("$baseUrl/item/") || url.startsWith("$baseUrl/location/"))

    val hasToken: Boolean get() = !secrets[SecretStore.HOMEBOX_TOKEN].isNullOrBlank()

    // ---- auth ---------------------------------------------------------------------------

    fun status(): JSONObject = JSONObject(call("GET", "/status", auth = false))

    /** e.g. "v0.27.1", or null if the server didn't say. */
    fun serverVersion(): String? = status().optJSONObject("build")?.optString("version")?.takeIf { it.isNotBlank() }

    /**
     * Logs in with username/password, then swaps the session for a long-lived API key named
     * "homebox-nfc (<phone model>)" so you don't have to log in again when the session expires.
     * The password is never stored.
     */
    fun login(username: String, password: String): String {
        val body = JSONObject()
            .put("username", username)
            .put("password", password)
            .put("stayLoggedIn", true)
        val session = JSONObject(call("POST", "/users/login", body, auth = false)).optString("token")
        if (session.isBlank()) throw HomeboxException("Homebox didn't return a login token.")
        secrets[SecretStore.HOMEBOX_TOKEN] = session

        return try {
            val created = JSONObject(
                call("POST", "/users/self/api-keys", JSONObject().put("name", "homebox-nfc (${Build.MODEL})"))
            )
            val apiKey = created.optString("token")
            if (apiKey.isNotBlank()) {
                secrets[SecretStore.HOMEBOX_TOKEN] = apiKey
                "Logged in (using an API key named \"homebox-nfc (${Build.MODEL})\")"
            } else {
                "Logged in (session token)"
            }
        } catch (e: HomeboxException) {
            "Logged in (session token; API key creation failed: ${e.message})"
        }
    }

    fun setApiKey(key: String) {
        secrets[SecretStore.HOMEBOX_TOKEN] = key.trim()
    }

    fun logout() {
        secrets[SecretStore.HOMEBOX_TOKEN] = null
    }

    /** Returns the logged-in user's name; a cheap end-to-end check of URL, access and auth. */
    fun whoAmI(): String {
        val user = JSONObject(call("GET", "/users/self")).optJSONObject("item")
        return user?.optString("name")?.takeIf { it.isNotBlank() } ?: user?.optString("email").orEmpty()
    }

    // ---- reads --------------------------------------------------------------------------

    fun tree(): List<TreeNode> = parseTree(JSONArray(call("GET", "/entities/tree?withItems=true")))

    private fun parseTree(array: JSONArray): List<TreeNode> = (0 until array.length()).map { i ->
        val o = array.getJSONObject(i)
        TreeNode(
            id = o.getString("id"),
            name = o.optString("name"),
            type = o.optString("type"),
            children = o.optJSONArray("children")?.let { parseTree(it) } ?: emptyList(),
        )
    }

    /** IDs of every entity carrying the given Homebox tag. */
    fun entityIdsWithTag(tagId: String): Set<String> {
        val ids = mutableSetOf<String>()
        var page = 1
        while (page <= 500) {
            val o = JSONObject(call("GET", "/entities?tags=${enc(tagId)}&page=$page&pageSize=100"))
            val items = o.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) ids += items.getJSONObject(i).getString("id")
            if (items.length() == 0 || ids.size >= o.optInt("total", 0)) break
            page++
        }
        return ids
    }

    /** Finds the tag by name (case-insensitive) or returns null. */
    fun findTag(name: String): String? {
        val tags = JSONArray(call("GET", "/tags"))
        for (i in 0 until tags.length()) {
            val t = tags.getJSONObject(i)
            if (t.optString("name").equals(name, ignoreCase = true)) return t.getString("id")
        }
        return null
    }

    fun ensureTag(name: String): String = findTag(name) ?: JSONObject(
        call(
            "POST", "/tags",
            JSONObject()
                .put("name", name)
                .put("description", "Has an NFC tag (written by homebox-nfc)")
        )
    ).getString("id")

    fun getEntity(id: String): JSONObject = JSONObject(call("GET", "/entities/${enc(id)}"))

    /** The parts of an entity this app cares about. */
    fun entityInfo(id: String): EntityInfo = EntityInfo.from(getEntity(id))

    /** Parses ".../item/<id>" or ".../location/<id>" on this server into (isLocation, id). */
    fun parseEntityUrl(url: String): Pair<Boolean, String>? {
        if (!isHomeboxUrl(url)) return null
        val parts = url.removePrefix("$baseUrl/").substringBefore('?').substringBefore('#').split('/')
        val id = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
        return (parts[0] == "location") to id
    }

    // ---- writes -------------------------------------------------------------------------

    /**
     * PATCH only touches what's given: [parentId] moves the entity (clearing any location
     * override when the new parent is a location), [locationId] sets an override, [tagIds]
     * replaces the tag list.
     */
    fun patch(id: String, parentId: String? = null, locationId: String? = null, tagIds: List<String>? = null) {
        val body = JSONObject().put("id", id)
        parentId?.let { body.put("parentId", it) }
        locationId?.let { body.put("locationId", it) }
        tagIds?.let { body.put("tagIds", JSONArray(it)) }
        call("PATCH", "/entities/${enc(id)}", body)
    }

    /** Sets custom text fields (adding them if missing) while preserving everything else. */
    fun setTextFields(id: String, values: Map<String, String>) {
        if (values.isEmpty()) return
        call("PUT", "/entities/${enc(id)}", toUpdate(getEntity(id), values))
    }

    /**
     * Adds Homebox tag [tagId] to the entity (PATCH, which leaves every other field alone),
     * then, if [fieldName] is set, records [fieldValue] in that custom text field.
     */
    fun markTagged(entityId: String, tagId: String, fieldName: String?, fieldValue: String) {
        val info = entityInfo(entityId)
        if (tagId !in info.tagIds) patch(entityId, tagIds = info.tagIds + tagId)
        if (!fieldName.isNullOrBlank()) setTextFields(entityId, mapOf(fieldName to fieldValue))
    }

    /**
     * Builds a full EntityUpdate from an EntityOut. PUT replaces every field, so we start from
     * the server's own copy and only rename the edge fields, which keeps everything else intact.
     */
    private fun toUpdate(e: JSONObject, values: Map<String, String>): JSONObject {
        val u = JSONObject(e.toString())
        for (k in listOf(
            "tags", "parent", "location", "entityType", "attachments", "children",
            "createdAt", "updatedAt", "imageId", "thumbnailId", "itemCount", "totalPrice",
        )) u.remove(k)

        u.put("tagIds", JSONArray(idsOf(e.optJSONArray("tags"))))
        e.optJSONObject("parent")?.optString("id")?.takeIf { it.isNotBlank() }?.let { u.put("parentId", it) }
        e.optJSONObject("entityType")?.optString("id")?.takeIf { it.isNotBlank() }?.let { u.put("entityTypeId", it) }
        // locationId is only present when the entity has its own location override; keep it as-is.
        if (u.optString("locationId").isBlank()) u.remove("locationId")

        val fields = e.optJSONArray("fields") ?: JSONArray()
        val newFields = JSONArray()
        val remaining = values.toMutableMap()
        for (i in 0 until fields.length()) {
            val f = JSONObject(fields.getJSONObject(i).toString())
            val key = remaining.keys.firstOrNull { it.equals(f.optString("name"), ignoreCase = true) }
            if (key != null) {
                f.put("type", "text").put("textValue", remaining.remove(key))
            }
            newFields.put(f)
        }
        for ((name, value) in remaining) {
            newFields.put(JSONObject().put("name", name).put("type", "text").put("textValue", value))
        }
        u.put("fields", newFields)
        return u
    }

    // ---- plumbing -----------------------------------------------------------------------

    private fun call(method: String, path: String, body: JSONObject? = null, auth: Boolean = true): String {
        if (baseUrl.isBlank()) {
            throw HomeboxException("Set your Homebox address in Settings first.", HomeboxException.Kind.NOT_CONFIGURED)
        }
        val rb = Request.Builder()
            .url("$baseUrl/api/v1$path")
            .header("Accept", "application/json")

        when (prefs.accessMode) {
            AccessMode.SERVICE_TOKEN -> {
                rb.header("CF-Access-Client-Id", prefs.cfClientId)
                rb.header("CF-Access-Client-Secret", secrets[SecretStore.CF_CLIENT_SECRET].orEmpty())
            }
            AccessMode.BROWSER_COOKIE -> secrets[SecretStore.CF_COOKIE]?.let {
                rb.header("Cookie", "CF_Authorization=$it")
            }
            else -> Unit
        }

        if (auth) {
            val token = secrets[SecretStore.HOMEBOX_TOKEN]
            if (token.isNullOrBlank()) {
                throw HomeboxException("Log in to Homebox in Settings.", HomeboxException.Kind.NEEDS_LOGIN)
            }
            rb.header("Authorization", if (token.startsWith("Bearer ")) token else "Bearer $token")
        }

        val requestBody = when {
            body != null -> body.toString().toByteArray(Charsets.UTF_8).toRequestBody(json)
            method in setOf("POST", "PUT", "PATCH") -> ByteArray(0).toRequestBody(json)
            else -> null
        }
        rb.method(method, requestBody)

        val response = try {
            http().newCall(rb.build()).execute()
        } catch (e: HomeboxException) {
            throw e
        } catch (e: IOException) {
            throw HomeboxException("Network error: ${e.message ?: e.javaClass.simpleName}")
        }

        response.use { r ->
            val text = r.body?.string().orEmpty()
            val contentType = r.header("Content-Type").orEmpty()
            if (r.code in 300..399) {
                val location = r.header("Location").orEmpty()
                if ("cloudflareaccess.com" in location || "/cdn-cgi/access" in location) throw accessBlocked()
                throw HomeboxException("Unexpected redirect to $location. Check the Homebox address.")
            }
            if (contentType.startsWith("text/html")) throw accessBlocked()
            if (r.code == 401) {
                throw if (auth) {
                    HomeboxException("Homebox login expired. Log in again in Settings.", HomeboxException.Kind.NEEDS_LOGIN)
                } else {
                    HomeboxException("Homebox rejected the username or password.", HomeboxException.Kind.NEEDS_LOGIN)
                }
            }
            if (!r.isSuccessful) throw HomeboxException("Homebox returned ${r.code}: ${errorText(text)}")
            return text
        }
    }

    private fun accessBlocked() = HomeboxException(
        "Cloudflare Access is blocking the app. Set up a client certificate, service token, or browser sign-in in Settings.",
        HomeboxException.Kind.ACCESS_BLOCKED,
    )

    @Synchronized
    fun resetConnection() {
        cachedHttp = null
    }

    @Synchronized
    private fun http(): OkHttpClient {
        val key = "${prefs.accessMode}|${prefs.certAlias}"
        cachedHttp?.let { (k, client) -> if (k == key) return client }

        val builder = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)

        if (prefs.accessMode == AccessMode.CLIENT_CERT) {
            val alias = prefs.certAlias
            if (alias.isBlank()) {
                throw HomeboxException("Choose a client certificate in Settings.", HomeboxException.Kind.NOT_CONFIGURED)
            }
            val keyManager = KeyChainKeyManager.load(context, alias)
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as KeyStore?)
            val trustManager = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
            val ssl = SSLContext.getInstance("TLS")
            ssl.init(arrayOf(keyManager), arrayOf(trustManager), null)
            builder.sslSocketFactory(ssl.socketFactory, trustManager)
        }

        return builder.build().also { cachedHttp = key to it }
    }

    private fun errorText(body: String): String = try {
        JSONObject(body).optString("error").ifBlank { body.take(200) }
    } catch (e: JSONException) {
        body.take(200)
    }

    private fun idsOf(array: JSONArray?): List<String> =
        if (array == null) emptyList() else (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }
        }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
