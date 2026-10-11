"""Shared API contracts: observed evidence and model hypotheses are separate."""
from datetime import datetime
from typing import Literal
from pydantic import BaseModel, ConfigDict, Field, model_validator

Id = str

class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")

class Event(StrictModel):
    event_id: str = Field(pattern=r"^[A-Za-z0-9_-]{1,100}$")
    session_id: str = Field(pattern=r"^[A-Za-z0-9_-]{1,100}$")
    app_id: str = Field(min_length=1, max_length=100)
    package_name: str = Field(min_length=1, max_length=200)
    timestamp: datetime
    event_type: Literal["CLICK", "SCROLL", "WINDOW_CHANGE", "APP_FOREGROUND", "APP_BACKGROUND"]
    text: str | None = Field(default=None, max_length=160)
    text_status: Literal["available", "missing", "redacted"]
    source: Literal["clicked_node", "descendant", "parent_context", "none"]
    observability: Literal["direct", "inferred", "unavailable"]
    data_origin: Literal["sample", "real"]
    consent_revision: str = Field(min_length=1, max_length=100)

    @model_validator(mode="after")
    def check_observation(self):
        if self.timestamp.tzinfo is None:
            raise ValueError("timezone required")
        if self.text_status == "available":
            if not self.text or not self.text.strip() or self.source == "none" or self.observability == "unavailable":
                raise ValueError("available text needs a source and observation")
        elif self.text is not None or self.source != "none" or self.observability != "unavailable":
            raise ValueError("missing/redacted must not retain raw text")
        return self

class SessionBatch(StrictModel):
    session_id: str = Field(pattern=r"^[A-Za-z0-9_-]{1,100}$")
    user_goal: str = Field(min_length=1, max_length=500)
    cloud_consent: bool
    consent_revision: str = Field(min_length=1, max_length=100)
    events: list[Event] = Field(min_length=1, max_length=500)

    @model_validator(mode="after")
    def check_session(self):
        if not self.cloud_consent:
            raise ValueError("explicit cloud consent required")
        if any(e.session_id != self.session_id or e.consent_revision != self.consent_revision for e in self.events):
            raise ValueError("session/consent mismatch")
        if len({e.event_id for e in self.events}) != len(self.events):
            raise ValueError("duplicate event IDs")
        if len({e.data_origin for e in self.events}) != 1:
            raise ValueError("sample/real cannot be mixed")
        return self

class Segment(StrictModel):
    from_event_id: str
    to_event_id: str
    topic: str = Field(min_length=1, max_length=300)
    intent_hypothesis: str = Field(min_length=1, max_length=500)
    evidence_ids: list[str] = Field(min_length=1, max_length=50)

class Transition(StrictModel):
    at_event_id: str
    hypothesis: str = Field(min_length=1, max_length=500)
    evidence_ids: list[str] = Field(min_length=1, max_length=50)

class Analysis(StrictModel):
    analysis_id: str
    session_id: str
    segments: list[Segment] = Field(max_length=30)
    transitions: list[Transition] = Field(max_length=30)
    goal_alignment: Literal["aligned", "possibly_diverged", "unknown"]
    uncertainty: str = Field(min_length=1, max_length=1000)
    user_confirmation_required: Literal[True]
    data_origin: Literal["sample", "real"]

    def validate_evidence(self, events: list[Event]):
        ordered = sorted(events, key=lambda e: (e.timestamp, e.event_id))
        positions = {e.event_id: i for i, e in enumerate(ordered)}
        ids = set(positions)
        if any(e.session_id != self.session_id or e.data_origin != self.data_origin for e in events):
            raise ValueError("analysis provenance mismatch")
        for s in self.segments:
            if s.from_event_id not in ids or s.to_event_id not in ids or not set(s.evidence_ids) <= ids:
                raise ValueError("fabricated evidence")
            if positions[s.from_event_id] > positions[s.to_event_id]:
                raise ValueError("reversed segment")
            if any(not positions[s.from_event_id] <= positions[e] <= positions[s.to_event_id] for e in s.evidence_ids):
                raise ValueError("evidence outside segment")
        for t in self.transitions:
            if t.at_event_id not in ids or not set(t.evidence_ids) <= ids:
                raise ValueError("fabricated transition")
        if not any(e.text_status == "available" and e.event_type == "CLICK" for e in events):
            if self.goal_alignment != "unknown" or self.segments or self.transitions:
                raise ValueError("insufficient observations must remain unknown")
        if self.goal_alignment != "unknown" and not self.segments:
            raise ValueError("alignment requires supporting segments")
        return self

class FeedbackInput(StrictModel):
    verdict: Literal["correct", "incorrect", "unknown"]
    corrected_intent: str | None = Field(default=None, max_length=500)

class LiveSnapshotInput(SessionBatch):
    snapshot_id: str = Field(pattern=r"^[A-Za-z0-9_-]{1,100}$")
    goal_revision: str = Field(min_length=1, max_length=100)
    automatic_consent: Literal[True]
    collection_consent: Literal[True]
    consent_since: datetime
    captured_at: datetime
    context_sequence: int = Field(strict=True, ge=1)
    generation: int = Field(strict=True, ge=0)
    events: list[Event] = Field(min_length=1, max_length=100)

    @model_validator(mode="after")
    def check_live_observation(self):
        if self.captured_at.tzinfo is None or self.consent_since.tzinfo is None:
            raise ValueError("timezone required")
        if self.consent_since > self.captured_at:
            raise ValueError("consent must precede capture")
        if any(e.timestamp < self.consent_since or e.timestamp > self.captured_at for e in self.events):
            raise ValueError("events outside consented snapshot")
        return self
