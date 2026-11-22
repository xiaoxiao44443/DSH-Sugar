@file:Suppress("DEPRECATION")

package app.dsh.sugar.mobile

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Copy Android's mutable Rect before its window or node handle is recycled. */
internal data class MainWindowBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun json() = JSONArray(listOf(left, top, right, bottom))

    companion object {
        fun from(rect: Rect) = MainWindowBounds(rect.left, rect.top, rect.right, rect.bottom)
    }
}

internal data class MainWindow(
    val id: Int, val packageName: String, val width: Int, val height: Int, val rotation: Int, val generation: Long,
    val bounds: MainWindowBounds? = null, val contentBounds: MainWindowBounds? = null,
    val focused: Boolean? = null, val layer: Int? = null,
) {
    val geometrySignature: String
        get() = UUID.nameUUIDFromBytes(toString().toByteArray(Charsets.UTF_8)).toString()

    fun metadata(): JSONObject? {
        val actualBounds = bounds ?: return null
        if (id < 0) return null
        return JSONObject().put("display_id", 0).put("window_id", id)
            .put("bounds", actualBounds.json()).put("bounds_source", "accessibility_window")
            .put("geometry_signature", geometrySignature).apply {
                if (packageName.isNotEmpty()) put("package", packageName)
                contentBounds?.let { put("content_bounds", it.json()) }
                focused?.let { put("focused", it) }
                layer?.let { put("layer", it) }
            }
    }
}

internal data class MainObservation(
    val id: String, val time: Long, val window: MainWindow,
    val nodes: Map<String, NodeRef>, val json: JSONObject,
)

/** System node identifiers are ephemeral; address by a child path and independently validate its contents. */
internal data class NodeRef(
    val path: List<Int>, val clazz: String, val resource: String, val text: String,
    val description: String, val bounds: Rect, val enabled: Boolean, val clickable: Boolean,
    val longClickable: Boolean, val editable: Boolean, val visible: Boolean, val password: Boolean,
    val checked: Boolean, val selected: Boolean,
) {
    companion object {
        fun read(node: AccessibilityNodeInfo, path: List<Int>): NodeRef {
            val password = node.isPassword
            fun chars(value: CharSequence?): String = value?.take(768)?.toString().orEmpty()
            return NodeRef(path, chars(node.className), node.viewIdResourceName.orEmpty(),
                if (password) "" else chars(node.text), if (password) "" else chars(node.contentDescription),
                Rect().also { node.getBoundsInScreen(it) }, node.isEnabled, node.isClickable, node.isLongClickable,
                node.isEditable, node.isVisibleToUser, password, node.isChecked, node.isSelected)
        }
    }

    fun json(id: String, parentId: String?): JSONObject = JSONObject()
        .put("id", id).put("parent_id", parentId ?: JSONObject.NULL)
        .put("class_name", clazz).put("resource_id", resource).put("text", text).put("description", description)
        .put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
        .put("enabled", enabled).put("visible", visible).put("clickable", clickable)
        .put("long_clickable", longClickable).put("editable", editable).put("password", password)
        .put("checked", checked).put("selected", selected)
}

internal class MainTree {
    val nodes = LinkedHashMap<String, NodeRef>()
    val json = JSONArray()
    private var truncated = false

    fun read(root: AccessibilityNodeInfo?): JSONObject {
        if (root != null) collect(root, emptyList(), null)
        return JSONObject().put("nodes", json).put("truncated", truncated)
    }

    private fun collect(node: AccessibilityNodeInfo, path: List<Int>, parentId: String?) {
        val id = "n" + nodes.size
        val info = NodeRef.read(node, path)
        nodes[id] = info
        json.put(info.json(id, parentId))
        for (i in 0 until node.childCount) {
            if (nodes.size >= 600 || path.size >= 64) {
                truncated = true
                break
            }
            val child = node.getChild(i) ?: continue
            try { collect(child, path + i, id) } finally { child.recycle() }
        }
    }
}

/** Root ownership stays with the caller. All additional node handles are recycled on Android 10–12. */
internal fun <T> withTarget(root: AccessibilityNodeInfo?, ref: NodeRef, use: (AccessibilityNodeInfo) -> T): T {
    var current = root ?: throw ScreenProblem("stale_target", "The target window is no longer available.")
    try {
        for (i in ref.path) {
            val next = current.getChild(i) ?: throw ScreenProblem("stale_target", "The node is no longer present.")
            if (current !== root) current.recycle()
            current = next
        }
        if (!current.refresh() || NodeRef.read(current, ref.path) != ref) {
            throw ScreenProblem("stale_target", "The target changed since the observation.")
        }
        if (!current.isEnabled || !current.isVisibleToUser) throw ScreenProblem("target_unavailable", "The node is hidden or disabled.")
        return use(current)
    } finally { if (current !== root) current.recycle() }
}
