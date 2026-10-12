package com.example.dopadopa

import com.example.dopadopa.intervention.OverlayDrag
import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayDragTest {
    @Test fun keepsTheOverlayInsideTheScreen() {
        assertEquals(0 to 0, OverlayDrag.clamp(-20, -8, 100, 40, 400, 800))
        assertEquals(300 to 760, OverlayDrag.clamp(900, 900, 100, 40, 400, 800))
        assertEquals(40 to 120, OverlayDrag.clamp(40, 120, 100, 40, 400, 800))
    }
}
