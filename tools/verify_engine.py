"""Real Agent Engine query with disposable Firebase identity and fictional events."""
import json
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
    evidence = {"status": "failed", "data_origin": "sample", "resource_name": resource}
    def identity(action, payload):
        response = requests.post("https://identitytoolkit.googleapis.com/v1/accounts:" + action,
            params={"key": key}, json=payload, timeout=30)
        response.raise_for_status()
        return response.json()
    def api(method, path, body=None):
        response = requests.request(method, base + path, headers={"Authorization": "Bearer " + token}, json=body, timeout=30)
        response.raise_for_status()
        return response.json()
    try:
        token = identity("signUp", {"email": "engine-" + secrets.token_hex(12) + "@example.invalid",
            "password": secrets.token_urlsafe(32), "returnSecureToken": True})["idToken"]
        batch = sample_batch("learning-only").model_dump(mode="json")
        api("POST", "/v1/sessions:batch", batch)
        generation = api("GET", "/v1/user/generation")["generation"]
        response = requests.post("https://asia-northeast1-aiplatform.googleapis.com/v1/" + resource + ":query",
            headers={"Authorization": "Bearer " + oauth}, timeout=90,
            json={"class_method": "query", "input": {"firebase_token": token, "session_id": batch["session_id"],
                "analysis_id": "engine-sample-analysis", "expected_generation": generation}})
        if response.status_code != 200:
            evidence["http_status"] = response.status_code
            # Classify allowlisted diagnostics without saving provider bodies.
            evidence["diagnostic_matches"] = [label for label in (
                "invalid generation", "invalid authentication", "PermissionDenied", "permission",
                "invalid ID", "ModuleNotFoundError", "unexpected keyword", "not found", "class_method")
                if label in response.text]
            raise RuntimeError("engine query failed")
        output = response.json()["output"]
        result = Analysis.model_validate(output["result"])
        result.validate_evidence([Event.model_validate(row) for row in batch["events"]])
        assert result.data_origin == "sample" and result.user_confirmation_required
        names = {row["tool"] for row in output["trace"]}
        assert {"get_user_goals", "get_timeline", "get_click_events"} <= names
        evidence.update(status="passed", goal_alignment=result.goal_alignment, tools=sorted(names), evidence_valid=True)
    except Exception as error:
        evidence["error_type"] = type(error).__name__
    finally:
        if token:
            api("DELETE", "/v1/user/data")
            assert api("GET", "/v1/sessions") == []
            identity("delete", {"idToken": token})
            evidence["temporary_account_cleaned"] = True
        (root / "artifacts/cloud/engine-smoke.json").write_text(json.dumps(evidence, indent=2), encoding="utf-8")
        print(json.dumps(evidence))
    if evidence["status"] != "passed": raise RuntimeError("engine smoke failed")

if __name__ == "__main__": main()
