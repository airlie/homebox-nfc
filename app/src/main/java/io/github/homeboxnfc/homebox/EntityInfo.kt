package io.github.homeboxnfc.homebox

import org.json.JSONObject

/** A trimmed-down EntityOut. */
data class EntityInfo(
    val id: String,
    val name: String,
    val isLocation: Boolean,
    /** Direct container (a location, or an item such as a box). */
    val parentId: String?,
    val parentName: String?,
    /** Only set when the entity has its own location override (parent is an item stored elsewhere). */
    val locationOverrideId: String?,
    /** Where Homebox says it is: the override, or the nearest location ancestor. */
    val locationId: String?,
    val locationName: String?,
    val tagIds: List<String>,
    /** Custom field name -> text value. */
    val fields: Map<String, String>,
) {
    fun field(name: String): String? = fields.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    companion object {
        fun from(o: JSONObject): EntityInfo {
            val tags = o.optJSONArray("tags")
            val fields = o.optJSONArray("fields")
            return EntityInfo(
                id = o.getString("id"),
                name = o.optString("name"),
                isLocation = o.optJSONObject("entityType")?.optBoolean("isLocation") == true,
                parentId = o.optJSONObject("parent")?.optString("id")?.ifBlank { null },
                parentName = o.optJSONObject("parent")?.optString("name"),
                locationOverrideId = o.optString("locationId").ifBlank { null },
                locationId = o.optJSONObject("location")?.optString("id")?.ifBlank { null },
                locationName = o.optJSONObject("location")?.optString("name"),
                tagIds = if (tags == null) emptyList() else (0 until tags.length()).mapNotNull {
                    tags.optJSONObject(it)?.optString("id")?.ifBlank { null }
                },
                fields = if (fields == null) emptyMap() else (0 until fields.length()).associate {
                    val f = fields.getJSONObject(it)
                    f.optString("name") to f.optString("textValue")
                },
            )
        }
    }
}
