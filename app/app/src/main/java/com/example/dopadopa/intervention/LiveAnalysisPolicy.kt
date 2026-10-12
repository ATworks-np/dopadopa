package com.example.dopadopa.intervention

/** Identity supplied by the authenticated runtime, never by model arguments. */
data class LiveContext(
    val ownerUid: String,
    val sessionId: String,
    val consentRevision: String,
    val goalRevision: String,
    val deletionGeneration: Long,
    val foregroundEpoch: Long
)

data class LiveEligibility(
    val goalConfirmed: Boolean,
    val collectionConsent: Boolean,
    val cloudConsent: Boolean,
    val automaticAnalysisConsent: Boolean,
    val authenticated: Boolean,
    val permissionsGranted: Boolean,
    val youtubeForeground: Boolean,
    val screenUnlocked: Boolean,
    val sensitiveUi: Boolean = false
) {
    val ready: Boolean get() = goalConfirmed && collectionConsent && cloudConsent &&
        automaticAnalysisConsent && authenticated && permissionsGranted && youtubeForeground &&
        screenUnlocked && !sensitiveUi
}

/** Timing fields use elapsedRealtime, independent of wall clock/time zone. No raw text here. */
data class LiveSnapshot(
    val snapshotId: String,
    val context: LiveContext,
    val clickSequence: Long,
    val sessionStartedElapsedMs: Long,
    val capturedElapsedMs: Long,
    val eventIds: Set<String>,
    val availableContentEventIds: Set<String>,
    val realData: Boolean
)

data class LiveAnalysisResult(
    val snapshotId: String,
    val context: LiveContext,
    val clickSequence: Long,
    val completed: Boolean,
    val possiblyDiverged: Boolean,
    val evidenceValidated: Boolean,
    val evidenceIds: Set<String>,
    val possiblyChaining: Boolean = false
)

/**
 * Bounded scheduling and authorization for an immediate suggestion, not a restriction action.
 * The caller must finish every reserved request, including errors/cancellation. A context change
 * cannot free the slot while its actual network request is still running. Display on the UI thread
 * immediately after finishAndAllowSuggestion, using a freshly read context and eligibility.
 * Durable budget/cooldown restoration and service wiring belong to DG-303/DG-307.
 */
class LiveAnalysisPolicy {
    companion object {
        const val MIN_SESSION_MS = 60_000L
        const val MIN_INTERVAL_MS = 60_000L
        const val HOUR_MS = 3_600_000L
        const val MAX_STARTS_PER_HOUR = 60
    }

    private var active: LiveSnapshot? = null
    private val starts = ArrayDeque<Long>()
    private var lastStart: Long? = null
    private var lastSuggestion: Long? = null
    private var lastClock: Long? = null

    @Synchronized fun nextAllowedStartElapsedMs(intervalMs: Long = MIN_INTERVAL_MS): Long =
        lastStart?.plus(intervalMs.coerceIn(MIN_INTERVAL_MS, 20 * MIN_INTERVAL_MS)) ?: 0

    @Synchronized
    fun reserve(
        snapshot: LiveSnapshot,
        currentContext: LiveContext,
        currentClickSequence: Long,
        eligibility: LiveEligibility,
        nowElapsedMs: Long,
        minimumIntervalMs: Long = MIN_INTERVAL_MS,
        immediate: Boolean = false
    ): Boolean {
        if (!clockValid(nowElapsedMs) || !eligibility.ready || active != null) return false
        if (!validContext(snapshot.context) || snapshot.context != currentContext ||
            snapshot.clickSequence < 0 ||
            snapshot.snapshotId.isBlank() || !snapshot.realData) return false
        if (snapshot.sessionStartedElapsedMs < 0 || snapshot.capturedElapsedMs < snapshot.sessionStartedElapsedMs ||
            snapshot.capturedElapsedMs > nowElapsedMs ||
            !immediate && nowElapsedMs - snapshot.sessionStartedElapsedMs < MIN_SESSION_MS) return false
        if (snapshot.eventIds.size !in 1..100 || snapshot.eventIds.any { it.isBlank() } ||
            !snapshot.eventIds.containsAll(snapshot.availableContentEventIds)) return false
        val interval = minimumIntervalMs.coerceIn(MIN_INTERVAL_MS, 20 * MIN_INTERVAL_MS)
        if (lastStart?.let { nowElapsedMs - it < if (immediate) MIN_INTERVAL_MS else interval } == true) return false
        while (starts.isNotEmpty() && nowElapsedMs - starts.first() >= HOUR_MS) starts.removeFirst()
        if (starts.size >= MAX_STARTS_PER_HOUR) return false
        // Reserve the budget before transport. Failures count and are not retried immediately.
        starts.addLast(nowElapsedMs)
        lastStart = nowElapsedMs
        active = snapshot.copy(eventIds = snapshot.eventIds.toSet(),
            availableContentEventIds = snapshot.availableContentEventIds.toSet())
        return true
    }

    @Synchronized
    fun finishFailure(snapshotId: String) {
        if (active?.snapshotId == snapshotId) active = null
    }

    @Synchronized
    fun finishAndAllowSuggestion(
        result: LiveAnalysisResult,
        currentContext: LiveContext,
        currentClickSequence: Long,
        eligibility: LiveEligibility,
        nowElapsedMs: Long,
        suggestionIntervalMinutes: Int = SuggestionInterval.DEFAULT_MINUTES
    ): Boolean {
        val snapshot = active ?: return false
        if (result.snapshotId != snapshot.snapshotId || result.context != snapshot.context ||
            result.clickSequence != snapshot.clickSequence) return false
        active = null
        if (!clockValid(nowElapsedMs) || !eligibility.ready || !snapshot.realData ||
            !result.completed || (!result.possiblyDiverged && !result.possiblyChaining) || !result.evidenceValidated) return false
        if (result.context != snapshot.context || currentContext != snapshot.context ||
            result.clickSequence != snapshot.clickSequence) return false
        if (nowElapsedMs < snapshot.capturedElapsedMs) return false
        if (result.evidenceIds.isEmpty() || !snapshot.eventIds.containsAll(result.evidenceIds) ||
            result.evidenceIds.none { it in snapshot.availableContentEventIds }) return false
        if (result.possiblyChaining && result.evidenceIds.count { it in snapshot.availableContentEventIds } < 3) return false
        if (lastSuggestion?.let { nowElapsedMs - it < SuggestionInterval.millis(suggestionIntervalMinutes) } == true) return false
        // A failed overlay attempt also consumes cooldown; it must not produce a retry storm.
        lastSuggestion = nowElapsedMs
        return true
    }

    private fun validContext(context: LiveContext): Boolean =
        context.ownerUid.isNotBlank() && context.sessionId.isNotBlank() &&
            context.consentRevision.isNotBlank() && context.goalRevision.isNotBlank() &&
            context.deletionGeneration >= 0 && context.foregroundEpoch >= 0

    private fun clockValid(now: Long): Boolean {
        if (now < 0 || lastClock?.let { now < it } == true) return false
        lastClock = now
        return true
    }
}
