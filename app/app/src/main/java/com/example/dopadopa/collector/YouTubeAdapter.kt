package com.example.dopadopa.collector

import android.view.accessibility.AccessibilityNodeInfo

object YouTubeAdapter {
    const val PACKAGE = "com.google.android.youtube"
    private val privateIds = Regex("edit|search|password|account|comment|chat|message|email|login|signin", RegexOption.IGNORE_CASE)

    private fun sensitive(node: AccessibilityNodeInfo): Boolean =
        node.isPassword || node.isEditable || (android.os.Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive) ||
            node.className?.toString()?.contains("EditText") == true ||
            privateIds.containsMatchIn(node.viewIdResourceName.orEmpty())

    fun observe(node: AccessibilityNodeInfo?): TextObservation {
        return runCatching { extract(node) }.getOrDefault(TextObservation.missing())
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
        val direct = TextObservation.from(node.text?.toString() ?: node.contentDescription?.toString(), "clicked_node")
        if (direct.status != "missing") return direct
        for (i in 0 until minOf(node.childCount, 12)) {
            val child = node.getChild(i) ?: continue
            val result = TextObservation.from(child.text?.toString() ?: child.contentDescription?.toString(), "descendant")
            if (result.status != "missing") return result
        }
        // Parent extraction is deliberately omitted until actual YouTube screens are verified.
        return TextObservation.missing()
    }

    private fun hasSensitiveDescendant(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (sensitive(node)) return true
        if (depth >= 2) return false
        for (i in 0 until minOf(node.childCount, 12)) {
            if (node.getChild(i)?.let { hasSensitiveDescendant(it, depth + 1) } == true) return true
        }
        return false
    }
}
