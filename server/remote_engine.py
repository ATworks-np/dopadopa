"""Cloud Run caller; end-user identity is reverified by the remote runtime."""
import json
import logging
import time
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
        credentials.refresh(lambda **kwargs: Request()(**(kwargs | {"timeout": 20})))
        payload = {"class_method": "query", "input": {"firebase_token": self.authorization[7:],
            "session_id": session_id, "analysis_id": analysis_id, "expected_generation": generation}}
        if snapshot_id is not None: payload["input"]["snapshot_id"] = snapshot_id
        self.failure_stage = "engine_transport"
        deadline = time.monotonic() + 110
        self.engine_http_status = None
        for attempt in range(2):
            if attempt:
                time.sleep(1)
                if self.store.generation(uid) != generation:
                    raise ConflictError("data deleted before retry")
                if snapshot_id is not None: view.get(uid, "sessions", session_id)
            remaining = deadline - time.monotonic()
            if remaining <= 0: raise RuntimeError("engine deadline")
            try:
                response_context = requests.post("https://" + match[2] + "-aiplatform.googleapis.com/v1/" + resource + ":query",
                headers={"Authorization": "Bearer " + credentials.token}, json=payload,
                timeout=(min(20, remaining), remaining), stream=True, allow_redirects=False)
            except requests.Timeout:
                self.failure_stage = "engine_deadline"
                raise RuntimeError("engine response timed out") from None
            with response_context as response:
                self.engine_http_status = response.status_code
                if response.status_code != 200:
                    self.failure_stage = "engine_http_rejection"
                    # Fixed metadata only: never log provider body, arguments, or tokens.
                    logging.getLogger("dopaguard").warning(
                        "engine_http_rejection http_status=%d attempt=%d", response.status_code, attempt + 1)
                    if attempt == 0 and response.status_code in {429, 502, 503, 504} and remaining > 6:
                        continue
                    raise RuntimeError("engine unavailable")
                content = bytearray()
                for chunk in response.iter_content(8192):
                    if time.monotonic() >= deadline: raise RuntimeError("engine deadline")
                    content.extend(chunk)
                    if len(content) > 300_000: raise ValueError("engine response too large")
                break
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
                {"get_user_goals", "get_timeline", "get_content_events", "get_click_events", "get_past_feedback"}
                or type(row["count"]) not in (int, float) or not 0 <= row["count"] <= 100 or row["count"] != int(row["count"])
                or type(row["elapsed_ms"]) not in (int, float) or not 0 <= row["elapsed_ms"] <= 120_000):
                raise ValueError("invalid trace")
        self.trace = rows
        return result, rows
