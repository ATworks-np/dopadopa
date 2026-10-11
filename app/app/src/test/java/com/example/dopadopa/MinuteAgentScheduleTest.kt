package com.example.dopadopa

import com.example.dopadopa.intervention.MinuteAgentSchedule
import org.junit.Assert.*
import org.junit.Test

class MinuteAgentScheduleTest {
    @Test fun runsEveryForegroundMinuteWithoutClicks() {
        val s = MinuteAgentSchedule()
        assertFalse(s.tick("youtube-owner-consent", false, 1_000))
        assertFalse(s.tick("youtube-owner-consent", false, 60_999))
        assertTrue(s.tick("youtube-owner-consent", false, 61_000))
        assertFalse(s.tick("youtube-owner-consent", false, 61_001))
        assertTrue(s.tick("youtube-owner-consent", false, 121_000))
    }
    @Test fun skipsRunningAndMissedMinutesInsteadOfQueueingThem() {
        val s = MinuteAgentSchedule(); s.tick("youtube", false, 0)
        assertFalse(s.tick("youtube", true, 60_000))
        assertFalse(s.tick("youtube", false, 65_000))
        assertTrue(s.tick("youtube", false, 120_000))
        assertTrue(s.tick("youtube", false, 300_000))
        assertFalse(s.tick("youtube", false, 300_001))
    }
    @Test fun leavingAndIdentityChangesInvalidateTheEpochAndRestartFirstMinute() {
        val s = MinuteAgentSchedule(); s.tick("owner-a", false, 0)
        val original = s.epoch
        assertFalse(s.tick(null, true, 59_000)); assertTrue(s.epoch > original)
        assertFalse(s.tick("owner-b", false, 60_000))
        assertFalse(s.tick("owner-b", false, 119_999))
        assertTrue(s.tick("owner-b", false, 120_000))
    }
    @Test fun preparationJitterDefersJustEnoughInsteadOfSkippingAWholeMinute() {
        val s = MinuteAgentSchedule(); s.tick("youtube", false, 0)
        assertTrue(s.tick("youtube", false, 60_000))
        s.deferUntil(122_000)
        assertFalse(s.tick("youtube", false, 120_000))
        assertTrue(s.tick("youtube", false, 122_000))
    }
}
