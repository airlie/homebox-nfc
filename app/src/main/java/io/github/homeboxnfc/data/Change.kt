package io.github.homeboxnfc.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * One queued update to a Homebox entity. Changes are stored on the phone and sent in order
 * when Homebox is reachable. Each one is idempotent (it says what the end state should be,
 * not what to toggle), so sending it twice after a dropped connection is harmless.
 */
data class Change(
    val id: Long,
    /** Homebox address the change was made against. */
    val server: String,
    val entityId: String,
    val entityName: String,
    /** Human-readable, e.g. "Moved to Garage › Shelf 2". */
    val summary: String,
    val createdAt: Long,
    /** Move the entity under this parent (a location, or a container item). */
    val parentId: String? = null,
    /** Add this Homebox tag (by name; created if missing). */
    val addTag: String? = null,
    /** Custom text fields to set. */
    val setFields: Map<String, String> = emptyMap(),
    /** Custom text fields to set only if they're currently empty. */
    val setFieldsIfEmpty: Map<String, String> = emptyMap(),
    /** Set when Homebox refused the change; it stays here until retried or discarded. */
    val error: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("server", server)
        .put("entityId", entityId)
        .put("entityName", entityName)
        .put("summary", summary)
        .put("createdAt", createdAt)
        .putOpt("parentId", parentId)
        .putOpt("addTag", addTag)
        .put("setFields", JSONObject(setFields as Map<*, *>))
        .put("setFieldsIfEmpty", JSONObject(setFieldsIfEmpty as Map<*, *>))
        .putOpt("error", error)

    companion object {
        fun fromJson(o: JSONObject) = Change(
            id = o.getLong("id"),
            server = o.optString("server"),
            entityId = o.getString("entityId"),
            entityName = o.optString("entityName"),
            summary = o.optString("summary"),
            createdAt = o.optLong("createdAt"),
            parentId = o.optString("parentId").ifBlank { null },
            addTag = o.optString("addTag").ifBlank { null },
            setFields = o.optJSONObject("setFields").toStringMap(),
            setFieldsIfEmpty = o.optJSONObject("setFieldsIfEmpty").toStringMap(),
            error = o.optString("error").ifBlank { null },
        )

        fun listToJson(list: List<Change>): String = JSONArray(list.map { it.toJson() }).toString()

        fun listFromJson(s: String): List<Change> = try {
            val a = JSONArray(s)
            (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }

        private fun JSONObject?.toStringMap(): Map<String, String> {
            if (this == null) return emptyMap()
            return keys().asSequence().associateWith { optString(it) }
        }
    }
}
