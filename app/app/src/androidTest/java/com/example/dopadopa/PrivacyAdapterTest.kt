package com.example.dopadopa

import android.view.accessibility.AccessibilityNodeInfo
import com.example.dopadopa.collector.YouTubeAdapter
import org.junit.Assert.*
import org.junit.Test

/** Synthetic nodes only: does not establish YouTube real-device observability. */
class PrivacyAdapterTest {
    @Test fun editablePasswordAndSensitiveIdsDoNotPersistText() {
        for (kind in listOf("editable", "password", "identity")) {
            val node = AccessibilityNodeInfo.obtain()
            node.text = "must not retain"
            when (kind) {
                "editable" -> node.isEditable = true
                "password" -> node.isPassword = true
                else -> node.viewIdResourceName = "com.google.android.youtube:id/search_query"
            }
            val result = YouTubeAdapter.observe(node)
            assertEquals("redacted", result.status)
            assertNull(result.text)
        }
    }
    @Test fun missingAndUnavailableNodeAreNotFilled() {
        assertEquals("missing", YouTubeAdapter.observe(null).status)
        val node = AccessibilityNodeInfo.obtain()
        node.text = "Kotlin"
        val result = YouTubeAdapter.observe(node)
        // An unsealed synthetic node cannot provide its parent: failure must stay missing.
        assertEquals("missing", result.status)
        assertNull(result.text)
    }
}
