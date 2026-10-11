"""Real Agent Engine query with disposable Firebase identity and fictional events."""
import json
from datetime import datetime, timezone
from pathlib import Path
import secrets
import subprocess
import requests
from server.samples import sample_batch
from server.models import Analysis, Event

def main():
    root = Path(__file__).resolve().parents[1]
    key = json.loads((root / "artifacts/cloud/firebase-web-config.json").read_text())["apiKey"]
    resource = json.loads((root / "artifacts/cloud/engine-operation-status.json").read_text())["resource_name"]
    oauth = subprocess.run([r"C:\Program Files (x86)\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd",
        "auth", "print-access-token", "--account=atomic1017@gmail.com"], capture_output=True, text=True, check=True).stdout.strip()
    base = "https://dopaguard-api-463106527119.asia-northeast1.run.app"
    token = None
    other_token = None
    evidence = {"status": "failed", "data_origin": "sample", "resource_name": resource,
        "started_at": datetime.now(timezone.utc).isoformat()}
    def identity(action, payload):
        response = requests.post("https://identitytoolkit.googleapis.com/v1/accounts:" + action,
            params={"key": key}, json=payload, timeout=30)
        response.raise_for_status()
        return response.json()
    def api(method, path, body=None):
        response = requests.request(method, base + path, headers={"Authorization": "Bearer " + token}, json=body, timeout=30)
        response.raise_for_status()
        return response.json()
    def query(user_token, sid, generation):
        return requests.post("https://asia-northeast1-aiplatform.googleapis.com/v1/" + resource + ":query",
            headers={"Authorization": "Bearer " + oauth}, timeout=90,
            json={"class_method": "query", "input": {"firebase_token": user_token, "session_id": sid,
                "analysis_id": "engine-sample-analysis", "expected_generation": generation}})
    try:
        token = identity("signUp", {"email": "engine-" + secrets.token_hex(12) + "@example.invalid",
            "password": secrets.token_urlsafe(32), "returnSecureToken": True})["idToken"]
        batch = sample_batch("learning-only").model_dump(mode="json")
        api("POST", "/v1/sessions:batch", batch)
        generation = api("GET", "/v1/user/generation")["generation"]
        response = query(token, batch["session_id"], generation)
        if response.status_code != 200:
            evidence["http_status"] = response.status_code
            # Classify allowlisted diagnostics without saving provider bodies.
            evidence["diagnostic_matches"] = [label for label in (
                "invalid generation", "invalid authentication", "PermissionDenied", "permission",
                "invalid ID", "ModuleNotFoundError", "unexpected keyword", "not found", "class_method",
                "RuntimeError", "ValueError", "PermissionError", "ValidationError", "DefaultCredentialsError",
                "event loop", "asyncio", "GEMINI_MODEL", "credential", "403", "401", "404", "setup",
                "app already exists", "api_key", "project", "location", "model", "KeyError")
                if label in response.text]
            raise RuntimeError("engine query failed")
        output = response.json()["output"]
        result = Analysis.model_validate(output["result"])
        result.validate_evidence([Event.model_validate(row) for row in batch["events"]])
        assert result.data_origin == "sample" and result.user_confirmation_required
        names = {row["tool"] for row in output["trace"]}
        assert {"get_user_goals", "get_timeline", "get_click_events"} <= names
        evidence.update(goal_alignment=result.goal_alignment, tools=sorted(names), evidence_valid=True)
        invalid = query("fictional-invalid-token", batch["session_id"], generation)
        assert invalid.status_code == 200 and invalid.json()["output"] == {"error": "analysis_unavailable"}
        other_token = identity("signUp", {"email": "engine-other-" + secrets.token_hex(12) + "@example.invalid",
            "password": secrets.token_urlsafe(32), "returnSecureToken": True})["idToken"]
        other = query(other_token, batch["session_id"], 0)
        assert other.status_code == 200 and other.json()["output"] == {"error": "record_unavailable"}
        api("DELETE", "/v1/user/data")
        stale = query(token, batch["session_id"], generation)
        assert stale.status_code == 200 and stale.json()["output"] == {"error": "generation_conflict"}
        evidence.update(status="passed", boundary_checks=["invalid Firebase token rejected",
            "other authenticated owner cannot investigate session", "stale generation rejected before model"])
    except Exception as error:
        evidence["error_type"] = type(error).__name__
    finally:
        if other_token:
            identity("delete", {"idToken": other_token})
            evidence["other_account_cleaned"] = True
        if token:
            api("DELETE", "/v1/user/data")
            assert api("GET", "/v1/sessions") == []
            identity("delete", {"idToken": token})
            evidence["temporary_account_cleaned"] = True
        (root / "artifacts/cloud/engine-smoke.json").write_text(json.dumps(evidence, indent=2), encoding="utf-8")
        print(json.dumps(evidence))
    if evidence["status"] != "passed": raise RuntimeError("engine smoke failed")

if __name__ == "__main__": main()
