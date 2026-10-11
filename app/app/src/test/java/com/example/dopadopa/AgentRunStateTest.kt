package com.example.dopadopa

import com.example.dopadopa.intervention.AgentRunState
import org.junit.Assert.*
import org.junit.Test

class AgentRunStateTest {
    @Test fun onlyActualRequestAndItsOwnCompletionChangeJudgingState() {
        val state = AgentRunState()
        assertFalse(state.judging)
        assertFalse(state.begin(""))
        assertTrue(state.begin("active-request"))
        assertTrue(state.judging)
        assertFalse(state.begin("overlapping-request"))
        state.finish("foreign-completion")
        assertTrue(state.judging)
        state.finish("active-request")
        assertFalse(state.judging)
    }
}
