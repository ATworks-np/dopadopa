package com.example.dopadopa

import com.example.dopadopa.intervention.*
import org.junit.Assert.*
import org.junit.Test

class LiveAnalysisPolicyTest {
    @Test fun goalAlignedButChainingResultCanSuggestRest() {
        val p = LiveAnalysisPolicy()
        val s = snapshot().copy(eventIds = setOf("t1", "t2", "t3"), availableContentEventIds = setOf("t1", "t2", "t3"))
        assertTrue(reserve(p, s))
        val r = result(s).copy(possiblyDiverged = false, possiblyChaining = true, evidenceIds = s.eventIds)
        assertTrue(p.finishAndAllowSuggestion(r, context, s.clickSequence, ready, 60_001))
    }
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

    @Test fun screenTitleEvidenceAllowsSuggestionWithoutClick() {
        val p = LiveAnalysisPolicy()
        val s = snapshot(sequence = 0).copy(eventIds = setOf("title", "scroll"), availableContentEventIds = setOf("title"))
        assertTrue(reserve(p, s))
        val r = result(s).copy(evidenceIds = setOf("title"))
        assertTrue(p.finishAndAllowSuggestion(r, context, 0, ready, 60_001))
    }

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
        assertTrue(reserve(LiveAnalysisPolicy(), snapshot(sequence = 0).copy(eventIds = setOf("foreground"), availableContentEventIds = emptySet())))
        assertTrue(reserve(LiveAnalysisPolicy(), snapshot()))
    }

    @Test fun rejectsSampleForeignContextAndInvalidObservationSets() {
        val invalid = listOf(snapshot().copy(realData = false),
            snapshot().copy(context = context.copy(ownerUid = "other")),
            snapshot().copy(availableContentEventIds = setOf("fabricated", "click-1")),
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

    @Test fun immediateRequestSkipsConfiguredSpacingButKeepsServerMinuteLimit() {
        val young = snapshot(captured = 1_000)
        val p = LiveAnalysisPolicy()
        assertFalse(reserve(p, young, 1_000))
        assertTrue(p.reserve(young, context, young.clickSequence, ready, 1_000, immediate = true))
        p.finishFailure(young.snapshotId)
        assertFalse(reserve(p, snapshot(3, 30_000), 30_000))
        assertFalse(p.reserve(snapshot(3, 30_000), context, 3, ready, 30_000, immediate = true))
        assertTrue(p.reserve(snapshot(3, 61_000), context, 3, ready, 61_000, minimumIntervalMs = 1_200_000, immediate = true))
        p.finishFailure("snapshot-3")
        assertFalse(p.reserve(snapshot(4, 90_000), context, 4, ready, 90_000, minimumIntervalMs = 120_000))
        assertTrue(p.reserve(snapshot(4, 181_000), context, 4, ready, 181_000, minimumIntervalMs = 120_000))
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
        assertTrue(p.finishAndAllowSuggestion(result(s), context, 3, ready, 60_001))
    }

    @Test fun revokedAndLockedResultsDoNotSuggestButElapsedTimeDoesNotDiscard() {
        val s = snapshot()
        listOf(ready.copy(cloudConsent = false), ready.copy(automaticAnalysisConsent = false),
            ready.copy(youtubeForeground = false), ready.copy(screenUnlocked = false)).forEach { gate ->
            val p = LiveAnalysisPolicy(); assertTrue(reserve(p, s))
            assertFalse(p.finishAndAllowSuggestion(result(s), context, 2, gate, 60_001))
        }
        val p = LiveAnalysisPolicy(); assertTrue(reserve(p, s))
        assertTrue(finish(p, s, 120_001))
    }

    @Test fun suggestionCooldownAppliesAcrossNewRequests() {
        val p = LiveAnalysisPolicy(); val s = snapshot()
        assertTrue(reserve(p, s)); assertTrue(finish(p, s))
        val next = snapshot(3, 120_000)
        assertTrue(reserve(p, next)); assertFalse(finish(p, next))
        val later = snapshot(4, 660_001)
        assertTrue(reserve(p, later)); assertTrue(finish(p, later))
    }

    @Test fun userCanShortenOrLengthenNotificationIntervalWithoutResettingPolicy() {
        val short = LiveAnalysisPolicy(); val initial = snapshot()
        assertTrue(reserve(short, initial)); assertTrue(finish(short, initial))
        val next = snapshot(3, 120_001)
        assertTrue(reserve(short, next))
        assertTrue(short.finishAndAllowSuggestion(result(next), context, 3, ready, 120_001, 1))

        val long = LiveAnalysisPolicy()
        assertTrue(reserve(long, initial)); assertTrue(finish(long, initial))
        val tenMinutes = snapshot(3, 660_001)
        assertTrue(reserve(long, tenMinutes))
        assertFalse(long.finishAndAllowSuggestion(result(tenMinutes), context, 3, ready, 660_001, 20))
        val twentyMinutes = snapshot(4, 1_260_001)
        assertTrue(reserve(long, twentyMinutes))
        assertTrue(long.finishAndAllowSuggestion(result(twentyMinutes), context, 4, ready, 1_260_001, 20))
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
