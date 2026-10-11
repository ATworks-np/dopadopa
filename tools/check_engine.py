"""Read the existing Agent Engine operation; never start another deployment."""
import json
from pathlib import Path
import subprocess
import requests

def main():
    root = Path(__file__).resolve().parents[1]
    recorded = json.loads((root / "artifacts/cloud/engine-operation.json").read_text())
    token = subprocess.run([r"C:\Program Files (x86)\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd",
        "auth", "print-access-token", "--account=atomic1017@gmail.com"],
        capture_output=True, text=True, check=True).stdout.strip()
    response = requests.get("https://asia-northeast1-aiplatform.googleapis.com/v1/" + recorded["operation_name"],
        headers={"Authorization": "Bearer " + token}, timeout=30)
    response.raise_for_status()
    operation = response.json()
    state = {"operation_name": recorded["operation_name"], "done": operation.get("done", False)}
    if "error" in operation:
        state.update(status="failed", error_code=operation["error"].get("code"),
            error_message=operation["error"].get("message"))
    elif state["done"]:
        state.update(status="deployed", resource_name=recorded["candidate_resource_name"])
    else: state["status"] = recorded.get("status_at_recording", "creating")
    (root / "artifacts/cloud/engine-operation-status.json").write_text(json.dumps(state, indent=2), encoding="utf-8")
    print(json.dumps(state))

if __name__ == "__main__": main()
