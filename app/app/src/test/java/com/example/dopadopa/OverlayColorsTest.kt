package com.example.dopadopa

import android.content.res.Configuration
import com.example.dopadopa.intervention.OverlayColors
import org.junit.Assert.*
import org.junit.Test

class OverlayColorsTest {
    @Test fun systemNightModeSelectsTheMatchingPalette() {
        assertFalse(OverlayColors.night(Configuration.UI_MODE_NIGHT_NO))
        assertTrue(OverlayColors.night(Configuration.UI_MODE_NIGHT_YES))
        assertFalse(OverlayColors.of(Configuration.UI_MODE_NIGHT_NO or Configuration.UI_MODE_TYPE_NORMAL).night)
        val dark = OverlayColors.of(Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
        assertTrue(dark.night)
        assertEquals(OverlayColors.dark.canvas, dark.canvas)
        assertEquals(OverlayColors.dark.accent, dark.accent)
        assertTrue(dark.ink != OverlayColors.light.ink)
    }
}
