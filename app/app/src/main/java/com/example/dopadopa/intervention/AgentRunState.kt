package com.example.dopadopa.intervention

/** Runtime owns this state. UI refreshes must never manufacture an analysis request. */
class AgentRunState {
    @Volatile private var requestId: String? = null
    val judging: Boolean get() = requestId != null

    @Synchronized fun begin(id: String): Boolean {
        if (id.isBlank() || requestId != null) return false
        requestId = id
        return true
    }

    @Synchronized fun finish(id: String) {
        if (requestId == id) requestId = null
    }

    companion object { val live = AgentRunState() }
}
