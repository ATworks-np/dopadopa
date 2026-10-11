package com.example.dopadopa

import com.example.dopadopa.collector.SessionTracker
import org.junit.Assert.*
import org.junit.Test

class SessionTrackerTest {
    private var sequence = 0
    private fun tracker() = SessionTracker("target") { "s${++sequence}" }
    @Test fun sessionStartsAndEndsWithObservedTimes() {
        val t = tracker()
        assertEquals("APP_FOREGROUND", t.foreground("target", 100).single().type)
        assertTrue(t.foreground("target", 110).isEmpty())
        assertEquals(120, t.foreground("other", 120).single().timestamp)
        assertNull(t.active)
    }
    @Test fun rejectsOldAndInvalidUsageEvents() {
        val t = tracker()
        assertTrue(t.foreground("target", -1).isEmpty())
        t.foreground("target", 100)
        assertTrue(t.background(90).isEmpty())
        assertNotNull(t.active)
    }
    @Test fun clickWithoutUsageDoesNotInventForeground() {
        val t = tracker()
        assertNotNull(t.observedInteraction(100))
        assertTrue(t.foreground("target", 110).isEmpty())
    }
    @Test fun recoveryDoesNotInventAnEndTime() {
        val t = tracker(); t.foreground("target", 100); t.reset()
        assertTrue(t.background(200).isEmpty())
        assertNull(t.active)
    }
}
