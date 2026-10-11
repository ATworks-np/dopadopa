package com.example.dopadopa.collector

/** Only observed timestamps are used. Process loss leaves an incomplete session. */
class SessionTracker(private val target: String, private val id: () -> String) {
    data class Change(val sessionId: String, val type: String, val timestamp: Long)
    var active: String? = null
        private set
    private var lastAt = 0L

    fun foreground(packageName: String, at: Long): List<Change> {
        if (at <= 0 || at < lastAt) return emptyList()
        lastAt = at
        if (packageName == target) {
            if (active != null) return emptyList()
            val session = id(); active = session
            return listOf(Change(session, "APP_FOREGROUND", at))
        }
        return background(at)
    }
    fun background(at: Long): List<Change> {
        if (at <= 0 || at < lastAt) return emptyList()
        lastAt = at
        val session = active ?: return emptyList()
        active = null
        return listOf(Change(session, "APP_BACKGROUND", at))
    }
    fun observedInteraction(at: Long): String? {
        if (at <= 0) return null
        // First observed click can establish a session, but never invents a foreground event.
        if (active == null) active = id()
        return active
    }
    fun reset() { active = null; lastAt = 0 }
}
