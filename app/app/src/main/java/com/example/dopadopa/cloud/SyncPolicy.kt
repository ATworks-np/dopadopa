package com.example.dopadopa.cloud

import com.example.dopadopa.data.*
import com.example.dopadopa.collector.TextObservation

/** Consent is checked at collection and again before a new upload. */
object SyncPolicy {
    fun permits(session: Session, events: List<InteractionEvent>, consent: ConsentRecord?,
        currentRevision: String, cloudAllowed: Boolean): Boolean {
        if (!cloudAllowed || consent == null || !consent.collection || !consent.cloud) return false
        if (session.endedAt == null || events.isEmpty() || events.size > 500) return false
        if (consent.revision != currentRevision) return false
        if (events.map { it.eventId }.toSet().size != events.size) return false
        return events.all { event ->
            event.sessionId == session.sessionId && event.dataOrigin == session.dataOrigin &&
                event.consentRevision == currentRevision && event.timestamp >= consent.createdAt &&
                event.timestamp in session.startedAt..session.endedAt &&
                (event.textStatus != "available" || TextObservation.from(event.text, event.source).status == "available") &&
                runCatching { event.validate() }.isSuccess
        }
    }
}
