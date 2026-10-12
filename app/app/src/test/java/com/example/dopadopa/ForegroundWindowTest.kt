package com.example.dopadopa

import com.example.dopadopa.collector.ForegroundWindow
import org.junit.Assert.*
import org.junit.Test

class ForegroundWindowTest {
    private val own = "com.example.dopadopa"
    private val youtube = "com.google.android.youtube"
    private fun window(layer: Int, type: Int, pkg: String?, active: Boolean = false, focused: Boolean = false) =
        ForegroundWindow.Window(layer, type, pkg, active, focused)

    @Test fun touchingOurOverlayKeepsUnderlyingYoutubeEligible() {
        val windows = listOf(window(10, 4, own, active = true), window(1, 1, youtube))
        assertEquals(1, ForegroundWindow.select(windows, own))
    }
    @Test fun otherForegroundAppCannotExposeYoutubeBehindIt() {
        assertNull(ForegroundWindow.select(listOf(window(10, 4, own, active = true),
            window(5, 1, "another.app"), window(1, 1, youtube)), own))
    }
    @Test fun keyboardOrSystemDialogBlocksCapture() {
        for (type in listOf(2, 3)) assertNull(ForegroundWindow.select(listOf(
            window(10, type, "system.ui", focused = true), window(1, 1, youtube)), own))
    }
    @Test fun unknownActiveWindowFailsClosed() {
        assertNull(ForegroundWindow.select(listOf(window(10, 4, null, active = true), window(1, 1, youtube)), own))
    }
    @Test fun ourApplicationScreenIsNotIgnored() {
        assertNull(ForegroundWindow.select(listOf(window(10, 1, own, active = true), window(1, 1, youtube)), own))
    }
}
