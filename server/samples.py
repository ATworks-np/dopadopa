"""Explicit fictional scenarios. These never substitute for real collector evidence."""
import json
from pathlib import Path
from server.models import Event, SessionBatch

SCENARIOS = {"learning-shorts": "学習 → Shorts", "learning-only": "学習継続", "unobservable": "観測不能"}

def sample_batch(scenario):
    if scenario not in SCENARIOS: raise KeyError(scenario)
    raw = json.loads((Path(__file__).resolve().parent.parent / "docs/samples/sample_timeline.json").read_text(encoding="utf-8"))
    session_id = "sample-" + scenario
    rows = raw["events"][:2] if scenario == "learning-only" else raw["events"]
    events = []
    for row in rows:
        row = row | {"session_id": session_id, "package_name": "com.google.android.youtube", "data_origin": "sample", "consent_revision": "sample-v1"}
        if scenario == "unobservable": row.update(text=None, text_status="missing", source="none", observability="unavailable")
        events.append(Event.model_validate(row))
    return SessionBatch(session_id=session_id, user_goal=raw["user_goal"], cloud_consent=True, consent_revision="sample-v1", events=events)
