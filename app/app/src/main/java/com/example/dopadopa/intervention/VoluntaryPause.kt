package com.example.dopadopa.intervention

/** Monotonic deadline; no model can start a pause. Service reconnect releases it. */
class VoluntaryPause {
    private var started: Long? = null
    fun approve(now: Long) { require(now >= 0); started = now }
    fun release() { started = null }
    fun remaining(now: Long): Long {
        val start = started ?: return 0
        if (now < start || now - start >= DURATION) { release(); return 0 }
        return DURATION - (now - start)
    }
    companion object { const val DURATION = 600_000L }
}
