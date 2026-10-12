"""Explicit fictional scenarios. These never substitute for real collector evidence."""
import json
from datetime import datetime, timedelta, timezone
from pathlib import Path
from server.models import Event, SessionBatch

SCENARIOS = {"learning-shorts": "学習 → Shorts", "learning-only": "学習継続", "unobservable": "観測不能",
    "related-chain": "数学の関連コンテンツを短い間隔で連鎖（架空）"}

def sample_batch(scenario):
    if scenario not in SCENARIOS: raise KeyError(scenario)
    if scenario == "related-chain":
        start = datetime(2026, 10, 12, 10, 0, tzinfo=timezone(timedelta(hours=9)))
        titles = ["数学の意外な雑学TOP10", "99%が間違える計算の裏技", "知らないと損する暗算テクニック",
            "天才しか解けない数のパズル", "驚きの数学トリック集", "数学者も困惑する難問",
            "学校で教わらない数字の秘密", "一瞬で解ける魔法の計算"]
        return SessionBatch(session_id="sample-related-chain", user_goal="数学を学習する", cloud_consent=True,
            consent_revision="sample-v1", events=[Event(event_id=f"chain-{i}", session_id="sample-related-chain",
                app_id="youtube", package_name="com.google.android.youtube", timestamp=start + timedelta(seconds=i * 8),
                event_type="WINDOW_CHANGE", text=title, text_status="available", source="parent_context",
                observability="inferred", data_origin="sample", consent_revision="sample-v1") for i, title in enumerate(titles)])
    raw = json.loads((Path(__file__).resolve().parent.parent / "docs/samples/sample_timeline.json").read_text(encoding="utf-8"))
    session_id = "sample-" + scenario
    rows = raw["events"][:2] if scenario == "learning-only" else raw["events"]
    events = []
    for row in rows:
        row = row | {"session_id": session_id, "package_name": "com.google.android.youtube", "data_origin": "sample", "consent_revision": "sample-v1"}
        if scenario == "unobservable": row.update(text=None, text_status="missing", source="none", observability="unavailable")
        events.append(Event.model_validate(row))
    return SessionBatch(session_id=session_id, user_goal=raw["user_goal"], cloud_consent=True, consent_revision="sample-v1", events=events)
