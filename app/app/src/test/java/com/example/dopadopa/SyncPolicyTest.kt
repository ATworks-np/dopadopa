package com.example.dopadopa

import com.example.dopadopa.cloud.SyncPolicy
import com.example.dopadopa.data.*
import org.junit.Assert.*
import org.junit.Test

class SyncPolicyTest {
    private val session = Session("s1", "youtube", "com.google.android.youtube", 10, 20)
    private val event = InteractionEvent("e1", "s1", "youtube", "com.google.android.youtube", 12,
        "CLICK", null, "redacted", "none", "unavailable", "real", "r1")
    private val consent = ConsentRecord("r1", true, true, 5)
    @Test fun allows_consented_closed_session_without_raw_redacted_text() {
        assertTrue(SyncPolicy.permits(session, listOf(event), consent, "r1", true))
        assertNull(event.text)
    }
    @Test fun denies_revoke_missing_consent_and_retroactive_grant() {
        assertFalse(SyncPolicy.permits(session, listOf(event), consent, "r1", false))
        assertFalse(SyncPolicy.permits(session, listOf(event), null, "r1", true))
        assertFalse(SyncPolicy.permits(session, listOf(event), consent.copy(cloud = false), "r1", true))
        assertFalse(SyncPolicy.permits(session, listOf(event), consent, "r2", true))
        assertFalse(SyncPolicy.permits(session, listOf(event), consent.copy(createdAt = 15), "r1", true))
    }
    @Test fun denies_open_oversize_and_mixed_provenance_sessions() {
        assertFalse(SyncPolicy.permits(session.copy(endedAt = null), listOf(event), consent, "r1", true))
        assertFalse(SyncPolicy.permits(session, List(501) { event }, consent, "r1", true))
        assertFalse(SyncPolicy.permits(session, listOf(event.copy(dataOrigin = "sample")), consent, "r1", true))
        assertFalse(SyncPolicy.permits(session, listOf(event.copy(sessionId = "other")), consent, "r1", true))
    }
    @Test fun denies_redacted_text_that_retains_source() {
        assertFalse(SyncPolicy.permits(session, listOf(event.copy(text = "secret")), consent, "r1", true))
        val identifier = event.copy(text = "private@example.invalid", textStatus = "available", source = "clicked_node", observability = "direct")
        assertFalse(SyncPolicy.permits(session, listOf(identifier), consent, "r1", true))
    }
}
