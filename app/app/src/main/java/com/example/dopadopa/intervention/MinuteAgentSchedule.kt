package com.example.dopadopa.intervention

/** Main-thread clock: foreground minutes, never completion-relative or catch-up bursts. */
class MinuteAgentSchedule {
    var epoch: Long = 0
        private set
    var startedAt: Long? = null
        private set
    private var key: String? = null
    private var nextAt: Long? = null
    private var intervalMs = 60_000L

    fun tick(currentKey: String?, busy: Boolean, now: Long, intervalMs: Long = 60_000L): Boolean {
        val interval = intervalMs.coerceIn(60_000L, 20 * 60_000L)
        if (currentKey != key) {
            key = currentKey; epoch++
            this.intervalMs = interval
            startedAt = if (currentKey == null) null else now
            nextAt = startedAt?.plus(interval)
        } else if (interval != this.intervalMs && startedAt != null) {
            this.intervalMs = interval
            val elapsed = now - startedAt!!
            nextAt = if (elapsed < interval) startedAt!! + interval
                else startedAt!! + (elapsed / interval + 1) * interval
        }
        val start = startedAt ?: return false
        val due = nextAt ?: return false
        if (now < due) return false
        nextAt = start + ((now - start) / interval + 1) * interval
        return !busy
    }

    fun remainingSeconds(now: Long): Long = maxOf(0, ((nextAt ?: now) - now + 999) / 1_000)

    /** Small preparation delays must not turn a minute into a skipped whole minute. */
    fun deferUntil(elapsed: Long) {
        nextAt = nextAt?.let { maxOf(it, elapsed) }
    }
}
