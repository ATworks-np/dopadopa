"""Cloud Run caller; end-user identity is reverified by the remote runtime."""
import json
import os
import re
import requests
import google.auth
from google.auth.transport.requests import Request
from server.models import Analysis, Event
from server.store import ConflictError

class RemoteEngineRunner:
    def __init__(self, store, authorization):
        if not authorization or not authorization.startswith("Bearer "):
            raise ValueError("authentication required")
        self.store, self.authorization, self.trace = store, authorization, []
        self.failure_stage = "configuration"

    def run(self, uid, session_id, analysis_id, *, snapshot_id=None):
        resource = os.environ["AGENT_ENGINE_RESOURCE"]
        match = re.fullmatch(r"projects/(dopadopa|463106527119)/locations/(asia-northeast1)/reasoningEngines/[0-9]+", resource)
        if not match: raise ValueError("invalid Engine resource")
        generation = self.store.generation(uid)
        if snapshot_id is not None:
            from server.live import SnapshotStoreView
            view = SnapshotStoreView(self.store, uid, snapshot_id)
            session = view.get(uid, "sessions", session_id)
        else: session = self.store.get(uid, "sessions", session_id)
        self.failure_stage = "service_credentials"
        credentials, _ = google.auth.default(scopes=["https://www.googleapis.com/auth/cloud-platform"])
        credentials.refresh(lambda **kwargs: Request()(**(kwargs | {"timeout": 10})))
        payload = {"class_method": "query", "input": {"firebase_token": self.authorization[7:],
            "session_id": session_id, "analysis_id": analysis_id, "expected_generation": generation}}
        if snapshot_id is not None: payload["input"]["snapshot_id"] = snapshot_id
        self.failure_stage = "engine_transport"
        with requests.post("https://" + match[2] + "-aiplatform.googleapis.com/v1/" + resource + ":query",
            headers={"Authorization": "Bearer " + credentials.token}, json=payload,
            timeout=(10, 55), stream=True, allow_redirects=False) as response:
            if response.status_code != 200:
                self.failure_stage = "engine_http_rejection"
                raise RuntimeError("engine unavailable")
            content = bytearray()
            for chunk in response.iter_content(8192):
                content.extend(chunk)
                if len(content) > 300_000: raise ValueError("engine response too large")
        self.failure_stage = "engine_output"
        output = json.loads(content)["output"]
        if "error" in output:
            stage = output.get("stage")
            if isinstance(stage, str) and stage in {"model_execution", "model_deadline", "result_validation"}:
                self.failure_stage = "engine_" + stage
            raise RuntimeError("engine analysis unavailable")
        self.failure_stage = "result_validation"
        result = Analysis.model_validate(output["result"])
        if result.session_id != session_id or result.analysis_id != analysis_id or result.data_origin != session["events"][0]["data_origin"]:
            raise ValueError("engine identity mismatch")
        result.validate_evidence([Event.model_validate(row) for row in session["events"]])
        self.failure_stage = "generation_validation"
        if self.store.generation(uid) != generation: raise ConflictError("data deleted during investigation")
        if snapshot_id is not None: view.get(uid, "sessions", session_id)
        self.failure_stage = "trace_validation"
        rows = output["trace"]
        if not isinstance(rows, list) or len(rows) > 12: raise ValueError("invalid trace")
        for row in rows:
            if (set(row) != {"tool", "count", "elapsed_ms"} or row["tool"] not in
                {"get_user_goals", "get_timeline", "get_click_events", "get_past_feedback"}
                or type(row["count"]) not in (int, float) or not 0 <= row["count"] <= 100 or row["count"] != int(row["count"])
                or type(row["elapsed_ms"]) not in (int, float) or not 0 <= row["elapsed_ms"] <= 60_000):
                raise ValueError("invalid trace")
        self.trace = rows
        return result, rows
