package com.example.dopadopa

import com.example.dopadopa.intervention.PauseClock
import org.junit.Assert.*
import org.junit.Test

class PauseClockTest {
    @Test fun showsMinutesAndSecondsWithoutDroppingALeadingZero() {
        assertEquals("10:00", PauseClock.text(600))
        assertEquals("1:05", PauseClock.text(65))
        assertEquals("0:00", PauseClock.text(0))
        assertEquals("0:00", PauseClock.text(-3))
    }

    @Test fun barShrinksWithTheRemainingTenMinutes() {
        assertEquals(1f, PauseClock.fraction(600))
        assertEquals(0.5f, PauseClock.fraction(300))
        assertEquals(0f, PauseClock.fraction(0))
        assertEquals("休止中、残り8分42秒", PauseClock.spoken(522))
    }
}
