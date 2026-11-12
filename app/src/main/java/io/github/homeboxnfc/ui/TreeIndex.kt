package io.github.homeboxnfc.ui

import io.github.homeboxnfc.homebox.TreeNode

data class LocationOption(val node: TreeNode, val depth: Int, val path: String)

/** Lookups over the /entities/tree result. */
class TreeIndex(val roots: List<TreeNode>) {
    private val byId = HashMap<String, TreeNode>()
    private val parentOf = HashMap<String, String>()

    /** Locations only, depth-first, for pickers. */
    val locationOptions: List<LocationOption>

    init {
        val options = mutableListOf<LocationOption>()
        fun walk(nodes: List<TreeNode>, parent: TreeNode?, depth: Int, parentPath: String) {
            for (n in nodes) {
                byId[n.id] = n
                if (parent != null) parentOf[n.id] = parent.id
                val path = if (parentPath.isEmpty()) n.name else "$parentPath › ${n.name}"
                if (n.isLocation) options += LocationOption(n, depth, path)
                walk(n.children, n, if (n.isLocation) depth + 1 else depth, path)
            }
        }
        walk(roots, null, 0, "")
        locationOptions = options
    }

    operator fun get(id: String): TreeNode? = byId[id]

    /** Every entry (locations and items). */
    val all: Collection<TreeNode> get() = byId.values

    val itemCount: Int get() = byId.values.count { !it.isLocation }

    fun parentOf(id: String): String? = parentOf[id]

    /** The nearest location above [id] (not counting [id] itself). */
    fun locationOf(id: String): String? {
        var cur = parentOf[id]
        while (cur != null) {
            if (byId[cur]?.isLocation == true) return cur
            cur = parentOf[cur]
        }
        return null
    }

    /** Path of whatever contains [id], e.g. "Garage › Shelf 2" for an item on that shelf. */
    fun containerPath(id: String): String = parentOf[id]?.let { pathOf(it) }.orEmpty()

    /** "Garage › Shelf 2" for a location (or item) id. */
    fun pathOf(id: String): String {
        val names = mutableListOf<String>()
        var cur: String? = id
        while (cur != null) {
            byId[cur]?.let { names += it.name }
            cur = parentOf[cur]
        }
        return names.asReversed().joinToString(" › ")
    }

    /** True if [id] is [ancestorId] or anywhere below it. */
    fun isWithin(id: String, ancestorId: String): Boolean {
        var cur: String? = id
        while (cur != null) {
            if (cur == ancestorId) return true
            cur = parentOf[cur]
        }
        return false
    }

    /**
     * Items whose location is [locationId] (or, if [includeSubLocations], anything below it):
     * direct children plus items nested inside container items.
     */
    fun itemsAt(locationId: String, includeSubLocations: Boolean): List<TreeNode> {
        val out = mutableListOf<TreeNode>()
        fun collect(nodes: List<TreeNode>) {
            for (n in nodes) {
                if (n.isLocation) {
                    if (includeSubLocations) collect(n.children)
                } else {
                    out += n
                    collect(n.children)
                }
            }
        }
        collect(byId[locationId]?.children.orEmpty())
        return out
    }
}

/** Sorts names the way people expect: case-insensitive, with numbers in order ("Box 2" before "Box 10"). */
object NaturalOrder : Comparator<String> {
    private val chunks = Regex("""\d+|\D+""")

    override fun compare(a: String, b: String): Int {
        val x = chunks.findAll(a.lowercase()).map { it.value }.toList()
        val y = chunks.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]
            val q = y[i]
            val c = if (p[0].isDigit() && q[0].isDigit()) {
                p.trimStart('0').length.compareTo(q.trimStart('0').length).takeIf { it != 0 }
                    ?: p.trimStart('0').compareTo(q.trimStart('0'))
            } else {
                p.compareTo(q)
            }
            if (c != 0) return c
        }
        return x.size.compareTo(y.size).takeIf { it != 0 } ?: a.compareTo(b)
    }
}
