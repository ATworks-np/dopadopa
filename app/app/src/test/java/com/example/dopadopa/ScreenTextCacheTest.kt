package com.example.dopadopa

import com.example.dopadopa.collector.*
import org.junit.Assert.*
import org.junit.Test

class ScreenTextCacheTest {
    private val target = ScreenTextCache.Target(1, "video", "0 0 100 100", "View")
    @Test fun matchesOnlyTheClickedItemAndMarksCachedContextAsInferred() {
        val cache = ScreenTextCache()
        cache.replace(100, mapOf(target to TextObservation.from("Kotlin tutorial", "clicked_node")))
        val label = cache.lookup(target, 101)
        assertEquals("Kotlin tutorial", label.text)
        assertEquals("parent_context", label.source)
        assertEquals("inferred", label.observability)
        for (other in listOf(target.copy(window = 2), target.copy(id = "other"),
            target.copy(bounds = "100 100 200 200"), target.copy(kind = "Button"), target.copy(identity = 1)))
            assertEquals("missing", cache.lookup(other, 101).status)
    }
    @Test fun screenChangesExpiryAndRevocationDoNotReuseOldLabels() {
        val cache = ScreenTextCache()
        cache.replace(100, mapOf(target to TextObservation.from("Kotlin", "clicked_node")))
        assertEquals("missing", cache.lookup(target, 99).status)
        assertEquals("missing", cache.lookup(target, 5_101).status)
        cache.replace(200, emptyMap())
        assertEquals("missing", cache.lookup(target, 201).status)
        cache.replace(300, mapOf(target to TextObservation.from("Kotlin", "clicked_node")))
        cache.clear()
        assertEquals("missing", cache.lookup(target, 301).status)
    }
}
