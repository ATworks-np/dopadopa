package com.example.dopadopa

import com.example.dopadopa.intervention.*
import org.junit.Assert.*
import org.junit.Test

class LiveAnalysisPolicyTest {
    private val context = LiveContext("owner", "session", "consent", "goal", 0, 1)
    private val ready = LiveEligibility(true, true, true, true, true, true, true, true)
    private fun snapshot(sequence: Long = 2, captured: Long = 60_000) = LiveSnapshot(
        "snapshot-$sequence", context, sequence, 0, captured,
        setOf("click-1", "click-2", "scroll"), setOf("click-1", "click-2"), true)
    private fun result(s: LiveSnapshot) = LiveAnalysisResult(
        s.snapshotId, s.context, s.clickSequence, true, true, true, setOf("click-1"))
    private fun reserve(p: LiveAnalysisPolicy, s: LiveSnapshot, now: Long = s.capturedElapsedMs) =
        p.reserve(s, context, s.clickSequence, ready, now)
    private fun finish(p: LiveAnalysisPolicy, s: LiveSnapshot, now: Long = s.capturedElapsedMs + 1) =
        p.finishAndAllowSuggestion(result(s), context, s.clickSequence, ready, now)

    @Test fun allConsentPermissionAndForegroundGatesAreRequired() {
        val denied = listOf(ready.copy(goalConfirmed = false), ready.copy(collectionConsent = false),
            ready.copy(cloudConsent = false), ready.copy(automaticAnalysisConsent = false),
            ready.copy(authenticated = false), ready.copy(permissionsGranted = false),
            ready.copy(youtubeForeground = false), ready.copy(screenUnlocked = false),
            ready.copy(sensitiveUi = true))
        denied.forEach { assertFalse(LiveAnalysisPolicy().reserve(snapshot(), context, 2, it, 60_000)) }
    }

    @Test fun requiresOneMinuteButNoClickThreshold() {
        assertFalse(reserve(LiveAnalysisPolicy(), snapshot(captured = 59_999)))
        assertTrue(reserve(LiveAnalysisPolicy(), snapshot(sequence = 0).copy(eventIds = setOf("foreground"), availableClickEventIds = emptySet())))
        assertTrue(reserve(LiveAnalysisPolicy(), snapshot()))
    }

    @Test fun rejectsSampleForeignContextAndInvalidObservationSets() {
        val invalid = listOf(snapshot().copy(realData = false),
            snapshot().copy(context = context.copy(ownerUid = "other")),
            snapshot().copy(availableClickEventIds = setOf("fabricated", "click-1")),
            snapshot().copy(eventIds = (1..101).map { "e-$it" }.toSet()),
            snapshot().copy(capturedElapsedMs = 60_001), snapshot().copy(snapshotId = ""))
        invalid.forEach { assertFalse(reserve(LiveAnalysisPolicy(), it, 60_000)) }
    }

    @Test fun holdsSingleSlotUntilActualTransportEndsEvenAfterContextChanges() {
        val p = LiveAnalysisPolicy(); val s = snapshot()
        assertTrue(reserve(p, s))
        val changed = snapshot(3, 120_000).copy(context = context.copy(consentRevision = "new"))
        assertFalse(p.reserve(changed, changed.context, 3, ready, 120_000))
        p.finishFailure("wrong-request")
        assertFalse(reserve(p, snapshot(3, 120_000)))
        p.finishFailure(s.snapshotId)
        assertTrue(reserve(p, snapshot(3, 120_000)))
    }

    @Test fun failedRequestConsumesMinuteIntervalWithoutRequiringNewClick() {
        val p = LiveAnalysisPolicy(); val s = snapshot()
        assertTrue(reserve(p, s)); p.finishFailure(s.snapshotId)
        assertFalse(reserve(p, snapshot(3, 119_999)))
        assertTrue(reserve(p, s.copy(snapshotId = "next-minute", capturedElapsedMs = 120_000)))
    }

    @Test fun hourlyBudgetCountsFailuresAndRecoversAtRollingBoundary() {
        val p = LiveAnalysisPolicy()
        repeat(60) { i ->
            val s = snapshot(i.toLong() + 2, 60_000L * (i + 1))
            assertTrue(reserve(p, s)); p.finishFailure(s.snapshotId)
        }
        assertFalse(reserve(p, snapshot(22, 3_659_999)))
        assertTrue(reserve(p, snapshot(22, 3_660_000)))
    }

    @Test fun validDivergenceAuthorizesOneSuggestionButNeverARestriction() {
        val p = LiveAnalysisPolicy(); val s = snapshot()
        assertTrue(reserve(p, s)); assertTrue(finish(p, s))
        assertFalse(finish(p, s))
    }

    @Test fun unknownFailedUnvalidatedAndFabricatedResultsNeverSuggest() {
        val s = snapshot()
        val invalid = listOf(result(s).copy(possiblyDiverged = false),
            result(s).copy(completed = false), result(s).copy(evidenceValidated = false),
            result(s).copy(evidenceIds = emptySet()),
            result(s).copy(evidenceIds = setOf("fabricated")),
            result(s).copy(evidenceIds = setOf("scroll")))
        invalid.forEach { r ->
            val p = LiveAnalysisPolicy(); assertTrue(reserve(p, s))
            assertFalse(p.finishAndAllowSuggestion(r, context, 2, ready, 60_001))
        }
    }

    @Test fun lateResultsAreRejectedAfterAnyIdentityOrForegroundEpochChange() {
        val s = snapshot()
        val changed = listOf(context.copy(ownerUid = "other"), context.copy(sessionId = "other"),
            context.copy(consentRevision = "new"), context.copy(goalRevision = "new"),
            context.copy(deletionGeneration = 1), context.copy(foregroundEpoch = 2))
        changed.forEach { c ->
            val p = LiveAnalysisPolicy(); assertTrue(reserve(p, s))
            assertFalse(p.finishAndAllowSuggestion(result(s), c, 2, ready, 60_001))
        }
        val p = LiveAnalysisPolicy(); assertTrue(reserve(p, s))
        assertFalse(p.finishAndAllowSuggestion(result(s), context, 3, ready, 60_001))
    }

    @Test fun revokedLockedAndStaleResultsDoNotSuggest() {
        val s = snapshot()
        listOf(ready.copy(cloudConsent = false), ready.copy(automaticAnalysisConsent = false),
            ready.copy(youtubeForeground = false), ready.copy(screenUnlocked = false)).forEach { gate ->
            val p = LiveAnalysisPolicy(); assertTrue(reserve(p, s))
            assertFalse(p.finishAndAllowSuggestion(result(s), context, 2, gate, 60_001))
        }
        val p = LiveAnalysisPolicy(); assertTrue(reserve(p, s))
        assertFalse(finish(p, s, 120_001))
    }

    @Test fun suggestionCooldownAppliesAcrossNewRequests() {
        val p = LiveAnalysisPolicy(); val s = snapshot()
        assertTrue(reserve(p, s)); assertTrue(finish(p, s))
        val next = snapshot(3, 120_000)
        assertTrue(reserve(p, next)); assertFalse(finish(p, next))
        val later = snapshot(4, 660_001)
        assertTrue(reserve(p, later)); assertTrue(finish(p, later))
    }

    @Test fun elapsedClockRollbackCannotResetBudget() {
        val p = LiveAnalysisPolicy(); val s = snapshot()
        assertTrue(reserve(p, s)); p.finishFailure(s.snapshotId)
        assertFalse(reserve(p, snapshot(3, 59_999)))
        assertTrue(reserve(p, snapshot(3, 120_000)))
    }

    @Test fun foreignResponseCannotFreeTheActualRunningSlot() {
        val p = LiveAnalysisPolicy(); val s = snapshot()
        assertTrue(reserve(p, s))
        assertFalse(p.finishAndAllowSuggestion(result(s).copy(context = context.copy(ownerUid = "other")),
            context, 2, ready, 60_001))
        assertFalse(reserve(p, snapshot(3, 120_000)))
        assertTrue(finish(p, s, 120_000))
    }
}
