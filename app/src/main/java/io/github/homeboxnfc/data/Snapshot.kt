package io.github.homeboxnfc.data

import io.github.homeboxnfc.homebox.TreeNode
import org.json.JSONArray
import org.json.JSONObject

/**
 * The slice of Homebox the app needs to work offline: the location/item tree (ids, names and
 * nesting only) and which entries already carry the NFC tag. Roughly 100 bytes per entry.
 */
data class Snapshot(
    val server: String,
    val fetchedAt: Long,
    /** The NFC tag name [taggedIds] was fetched for. */
    val tagName: String,
    val roots: List<TreeNode>,
    val taggedIds: Set<String>,
) {
    fun toJson(): String = JSONObject()
        .put("server", server)
        .put("fetchedAt", fetchedAt)
        .put("tagName", tagName)
        .put("tagged", JSONArray(taggedIds.toList()))
        .put("tree", treeToJson(roots))
        .toString()

    /**
     * This snapshot with [changes] applied on top: moved entries sit under their new parent and
     * tagged entries count as tagged. Used both for the live view (saved data + pending
     * changes) and to fold a change in once Homebox has accepted it.
     */
    fun with(changes: List<Change>): Snapshot {
        if (changes.isEmpty()) return this
        var tagged = taggedIds
        val moves = mutableListOf<Change>()
        for (c in changes) {
            if (c.addTag != null && c.addTag.equals(tagName, ignoreCase = true)) tagged = tagged + c.entityId
            if (c.parentId != null) moves += c
        }
        if (moves.isEmpty()) return copy(taggedIds = tagged)

        val byId = HashMap<String, MNode>()
        val parentOf = HashMap<String, MNode>()
        fun build(n: TreeNode, parent: MNode?): MNode {
            val m = MNode(n.id, n.name, n.type)
            byId[n.id] = m
            if (parent != null) parentOf[n.id] = parent
            n.children.forEach { m.children += build(it, m) }
            return m
        }
        val top = roots.map { build(it, null) }.toMutableList()

        for (c in moves) {
            val target = byId[c.parentId] ?: continue
            // Entries added to Homebox after the snapshot was taken get a placeholder node.
            val node = byId[c.entityId] ?: MNode(c.entityId, c.entityName, "item").also { byId[it.id] = it }
            var cur: MNode? = target
            var cycle = false
            while (cur != null) {
                if (cur === node) { cycle = true; break }
                cur = parentOf[cur.id]
            }
            if (cycle) continue
            val old = parentOf[node.id]
            if (old != null) old.children.remove(node) else top.remove(node)
            target.children += node
            parentOf[node.id] = target
        }
        fun freeze(m: MNode): TreeNode = TreeNode(m.id, m.name, m.type, m.children.map(::freeze))
        return copy(roots = top.map(::freeze), taggedIds = tagged)
    }

    private class MNode(val id: String, val name: String, val type: String) {
        val children = mutableListOf<MNode>()
    }

    companion object {
        fun fromJson(s: String): Snapshot? = try {
            val o = JSONObject(s)
            val tagged = o.optJSONArray("tagged") ?: JSONArray()
            Snapshot(
                server = o.getString("server"),
                fetchedAt = o.getLong("fetchedAt"),
                tagName = o.optString("tagName"),
                roots = treeFromJson(o.getJSONArray("tree")),
                taggedIds = (0 until tagged.length()).map { tagged.getString(it) }.toSet(),
            )
        } catch (e: Exception) {
            null
        }

        private fun treeToJson(nodes: List<TreeNode>): JSONArray = JSONArray(nodes.map { n ->
            JSONObject().put("i", n.id).put("n", n.name).put("t", n.type).also {
                if (n.children.isNotEmpty()) it.put("c", treeToJson(n.children))
            }
        })

        private fun treeFromJson(a: JSONArray): List<TreeNode> = (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            TreeNode(
                id = o.getString("i"),
                name = o.optString("n"),
                type = o.optString("t"),
                children = o.optJSONArray("c")?.let { treeFromJson(it) } ?: emptyList(),
            )
        }
    }
}
