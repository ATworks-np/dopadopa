package com.example.dopadopa.collector

import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Rect

object YouTubeAdapter {
    const val PACKAGE = "com.google.android.youtube"
    private val privateIds = Regex("edit|search|password|account|comment|chat|message|email|login|signin", RegexOption.IGNORE_CASE)

    fun contentTitle(root: AccessibilityNodeInfo?, labels: Map<ScreenTextCache.Target, TextObservation>): TextObservation? {
        // The watch page replaces its named TextView with a full-width virtual title button.
        val player = root?.findAccessibilityNodeInfosByViewId("$PACKAGE:id/player_overlays")?.firstOrNull()
            ?: return null
        val playerBounds = Rect().also { player.getBoundsInScreen(it) }
        val rootBounds = Rect().also { root.getBoundsInScreen(it) }
        if (playerBounds.width() < rootBounds.width() * 0.9) return null
        fun isTitle(value: TextObservation) = value.status == "available" &&
            !value.text.orEmpty().contains(Regex("高評価.*件|回視聴|チャンネル登録|その他|チャンネル.*に移動|動画を再生"))
        labels.entries.firstOrNull {
            val bounds = Rect.unflattenFromString(it.key.bounds)
            it.key.id == "$PACKAGE:id/title" && bounds != null &&
                bounds.top in playerBounds.bottom..(playerBounds.bottom + playerBounds.height() / 4) &&
                bounds.width() >= playerBounds.width() * 0.9 && isTitle(it.value)
        }?.let { return it.value }
        return labels.entries.firstOrNull {
            val bounds = Rect.unflattenFromString(it.key.bounds)
            it.key.kind == "android.widget.Button" && bounds != null &&
                bounds.top == playerBounds.bottom && bounds.width() >= playerBounds.width() * 0.9 &&
                isTitle(it.value)
        }?.value
    }

    private fun sensitive(node: AccessibilityNodeInfo): Boolean =
        node.isPassword || node.isEditable || (android.os.Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive) ||
            node.className?.toString()?.contains("EditText") == true ||
            privateIds.containsMatchIn(node.viewIdResourceName.orEmpty())

    fun observe(node: AccessibilityNodeInfo?): TextObservation {
        return runCatching { extract(node) }.getOrDefault(TextObservation.missing())
    }

    fun target(node: AccessibilityNodeInfo): ScreenTextCache.Target {
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        return ScreenTextCache.Target(node.windowId, node.viewIdResourceName.orEmpty(),
            bounds.flattenToString(), node.className?.toString().orEmpty(), node.hashCode())
    }

    /** No live AccessibilityNodeInfo is retained after this call. */
    fun screen(root: AccessibilityNodeInfo?, diagnostic: (String) -> Unit = {},
               protectedNodes: (Int) -> Unit = {}): Map<ScreenTextCache.Target, TextObservation> = runCatching {
        if (root == null || root.packageName?.toString() != PACKAGE) {
            diagnostic("no_youtube_root"); return@runCatching emptyMap()
        }
        val queue = ArrayDeque<Triple<AccessibilityNodeInfo, Int, Int>>()
        val nodes = mutableListOf<Pair<AccessibilityNodeInfo, Int>>()
        var protectedCount = 0
        queue.add(Triple(root, 0, -1))
        while (queue.isNotEmpty()) {
            val (node, depth, parent) = queue.removeFirst()
            // Abort before reading text on an input/private screen or an incomplete tree.
            if (nodes.size >= 500 || depth > 40) { diagnostic("tree_limit"); return@runCatching emptyMap() }
            if (node.isPassword || node.isEditable) { diagnostic("input_screen"); return@runCatching emptyMap() }
            if (android.os.Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive) protectedCount++
            if (sensitive(node)) continue
            val index = nodes.size
            nodes.add(node to parent)
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(Triple(it, depth + 1, index)) }
        }
        val own = nodes.map { (node, _) ->
            if (node.isVisibleToUser) label(node, "clicked_node") else TextObservation.missing()
        }
        val subtree = own.toMutableList()
        for (i in nodes.indices.reversed()) {
            val parent = nodes[i].second
            if (parent >= 0 && subtree[parent].status == "missing" && subtree[i].status == "available")
                subtree[parent] = subtree[i].copy(source = "descendant", observability = "inferred")
        }
        val result = nodes.indices.mapNotNull { i ->
            val node = nodes[i].first
            val observed = if (node.isClickable) subtree[i] else own[i]
            if (observed.status == "available") target(node) to observed else null
        }.toMap()
        diagnostic(if (result.isEmpty()) "no_labels" else "labels_available")
        protectedNodes(protectedCount)
        result
    }.onFailure { diagnostic("node_unavailable") }.getOrDefault(emptyMap())

    private fun label(node: AccessibilityNodeInfo, source: String): TextObservation {
        val text = node.text?.toString()?.takeIf { it.isNotBlank() }
        return if (text != null) TextObservation.from(text, source)
            else TextObservation.fromDescription(node.contentDescription?.toString(), source)
    }

    private fun extract(node: AccessibilityNodeInfo?): TextObservation {
        if (node == null) return TextObservation.missing()
        // Inspect context for sensitive controls before reading any text.
        var ancestor: AccessibilityNodeInfo? = node
        repeat(4) {
            val current = ancestor
            if (current != null && sensitive(current)) return TextObservation.redacted()
            ancestor = current?.parent
        }
        if (hasSensitiveDescendant(node, 0)) return TextObservation.redacted()
        val direct = label(node, "clicked_node")
        if (direct.status != "missing") return direct
        val children = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        for (i in 0 until minOf(node.childCount, 40)) node.getChild(i)?.let { children.add(it to 1) }
        var count = 0
        while (children.isNotEmpty()) {
            val (child, depth) = children.removeFirst()
            if (++count > 100) break
            if (sensitive(child)) return TextObservation.redacted()
            val result = label(child, "descendant")
            if (result.status != "missing") return result
            if (depth < 6) for (i in 0 until minOf(child.childCount, 40))
                child.getChild(i)?.let { children.add(it to depth + 1) }
        }
        // Read siblings only within the nearest clickable item, never the whole screen.
        var parent = node.parent
        repeat(3) {
            val current = parent ?: return TextObservation.missing()
            if (sensitive(current)) return TextObservation.redacted()
            if (current.isClickable && !hasSensitiveDescendant(current, 0)) {
                val result = label(current, "parent_context")
                if (result.status != "missing") return result
                for (i in 0 until minOf(current.childCount, 40)) {
                    val child = current.getChild(i) ?: continue
                    if (sensitive(child)) return TextObservation.redacted()
                    val childLabel = label(child, "parent_context")
                    if (childLabel.status != "missing") return childLabel
                }
            }
            parent = current.parent
        }
        return TextObservation.missing()
    }

    private fun hasSensitiveDescendant(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (sensitive(node)) return true
        if (depth >= 6) return node.childCount > 0
        if (node.childCount > 40) return true
        for (i in 0 until node.childCount) {
            if (node.getChild(i)?.let { hasSensitiveDescendant(it, depth + 1) } == true) return true
        }
        return false
    }
}
