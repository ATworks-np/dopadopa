package com.example.dopadopa.intervention

/** Main-thread clock: foreground minutes, never completion-relative or catch-up bursts. */
class MinuteAgentSchedule {
    var epoch: Long = 0
        private set
    var startedAt: Long? = null
        private set
    private var key: String? = null
    private var nextAt: Long? = null

    fun tick(currentKey: String?, busy: Boolean, now: Long): Boolean {
        if (currentKey != key) {
            key = currentKey; epoch++
            startedAt = if (currentKey == null) null else now
            nextAt = startedAt?.plus(60_000)
        }
        val start = startedAt ?: return false
        val due = nextAt ?: return false
        if (now < due) return false
        nextAt = start + ((now - start) / 60_000 + 1) * 60_000
        return !busy
    }

    fun remainingSeconds(now: Long): Long = maxOf(0, ((nextAt ?: now) - now + 999) / 1_000)

    /** Small preparation delays must not turn a minute into a skipped whole minute. */
    fun deferUntil(elapsed: Long) {
        nextAt = nextAt?.let { maxOf(it, elapsed) }
    }
}
