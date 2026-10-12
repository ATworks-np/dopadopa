package com.example.dopadopa

import com.example.dopadopa.collector.PipControls
import org.junit.Assert.*
import org.junit.Test

class PipControlsTest {
    @Test fun pauseControlIsThePauseActionNotThePlayAction() {
        assertTrue(PipControls.isPauseControl("android.widget.ImageButton", "com.google.android.youtube:id/player_control_play_pause_replay_button", "一時停止"))
        assertFalse(PipControls.isPauseControl("android.widget.ImageButton", "com.google.android.youtube:id/player_control_play_pause_replay_button", "再生"))
        assertFalse(PipControls.isPauseAction("少し休みますか？"))
    }

    @Test fun onlySystemChromeCountsAsThePipCloseButton() {
        assertTrue(PipControls.isSystemClose("com.android.systemui", "com.android.systemui:id/dismiss", null))
        assertTrue(PipControls.isSystemClose("com.android.wm.shell", null, "閉じる"))
        assertFalse(PipControls.isSystemClose("com.google.android.youtube", "com.google.android.youtube:id/close", "閉じる"))
    }
}
