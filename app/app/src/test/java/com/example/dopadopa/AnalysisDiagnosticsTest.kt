package com.example.dopadopa

import com.example.dopadopa.intervention.AnalysisDiagnostics
import org.junit.Assert.*
import org.junit.Test

class AnalysisDiagnosticsTest {
    @Test fun keepsFixedStageAndDropsProviderText() {
        assertEquals("engine_transport", AnalysisDiagnostics.serverStage("engine_transport"))
        assertEquals("unknown", AnalysisDiagnostics.serverStage("secret observation eyJaaaa.bbbbbbbbbbbbbbbb.cccc"))
        assertEquals("unknown", AnalysisDiagnostics.serverStage("device_transport"))
        val line = AnalysisDiagnostics.line("19:27:07", "failed", "engine_transport")
        assertEquals("19:27:07 status=failed stage=engine_transport alignment=unknown tools=none", line)
        assertTrue(AnalysisDiagnostics.validLine(line))
    }

    @Test fun toolNamesStayInsideTheClosedSet() {
        val line = AnalysisDiagnostics.line("19:28:01", "insufficient_data", "unknown", "unknown",
            AnalysisDiagnostics.tools(listOf("get_user_goals", "get_timeline", "get_click_events")))
        assertEquals("19:28:01 status=insufficient_data stage=unknown alignment=unknown tools=get_user_goals,get_timeline,get_click_events", line)
        assertEquals("invalid", AnalysisDiagnostics.tools(listOf("get_user_goals", "read_screen")))
        assertFalse(AnalysisDiagnostics.validLine("19:28:01 status=failed stage=engine_transport alignment=unknown tools=動画を見た"))
    }
}
