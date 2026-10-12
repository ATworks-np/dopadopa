package com.example.dopadopa

import com.example.dopadopa.intervention.VoluntaryPause
import org.junit.Assert.assertEquals
import org.junit.Test

class VoluntaryPauseTest {
    @Test fun noApprovalMeansNoPause() {
        assertEquals(0L, VoluntaryPause().remaining(10_000))
    }
    @Test fun expiresAtTenMinutesAndDoesNotResurrect() {
        val pause = VoluntaryPause()
        pause.approve(1000)
        assertEquals(600_000L, pause.remaining(1000))
        assertEquals(1L, pause.remaining(600_999))
        assertEquals(0L, pause.remaining(601_000))
        assertEquals(0L, pause.remaining(2000))
    }
    @Test fun releaseAndClockResetAllowUseImmediately() {
        val pause = VoluntaryPause()
        pause.approve(1000); pause.release()
        assertEquals(0L, pause.remaining(2000))
        pause.approve(3000)
        assertEquals(0L, pause.remaining(10))
        assertEquals(0L, pause.remaining(3001))
    }
}
