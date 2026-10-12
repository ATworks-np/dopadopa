"""Invitation-only APK distribution. Never print or persist direct download URLs."""
import argparse
import hashlib
import json
import subprocess
import time
from pathlib import Path
import requests
from tools.cloud_cli import gcloud_executable

ROOT = Path(__file__).resolve().parents[1]
APP = "projects/463106527119/apps/1:463106527119:android:bec70e43d1f1dc528785fa"
BASE = "https://firebaseappdistribution.googleapis.com"
EVIDENCE = ROOT / "artifacts/cloud/app-distribution.json"

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="Read the recorded upload without starting another")
    parser.add_argument("--tester", help="Explicitly authorized recipient; sends a Firebase invitation")
    args = parser.parse_args()
    if not args.check and not args.tester:
        parser.error("Invitation-only distribution requires an explicitly authorized --tester")
    token = subprocess.run([gcloud_executable(), "auth", "print-access-token", "--account=atomic1017@gmail.com"],
        capture_output=True, text=True, check=True).stdout.strip()
    session = requests.Session()
    session.headers["Authorization"] = "Bearer " + token
    session.headers["x-goog-user-project"] = "dopadopa"
    def call(method, path, **kwargs):
        response = session.request(method, BASE + path, timeout=120, **kwargs)
        if not response.ok:
            raise RuntimeError("App Distribution HTTP " + str(response.status_code))
        return response.json()
    apk = ROOT / "app/app/build/outputs/apk/debug/app-debug.apk"
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    state = json.loads(EVIDENCE.read_text()) if EVIDENCE.exists() else {}
    if args.check:
        if not state.get("operation_name"): raise RuntimeError("No recorded upload operation")
    elif state.get("apk_sha256") != digest or not state.get("operation_name"):
        with apk.open("rb") as binary:
            operation = call("POST", "/upload/v1/" + APP + "/releases:upload", data=binary,
                headers={"X-Goog-Upload-File-Name": apk.name, "X-Goog-Upload-Protocol": "raw",
                    "Content-Type": "application/octet-stream"})
        state = {"project": "dopadopa", "apk_sha256": digest, "operation_name": operation["name"], "status": "uploading"}
        EVIDENCE.write_text(json.dumps(state, indent=2), encoding="utf-8")
    operation = call("GET", "/v1/" + state["operation_name"])
    for _ in range(10):
        if operation.get("done"): break
        time.sleep(2)
        operation = call("GET", "/v1/" + state["operation_name"])
    if not operation.get("done"):
        print(json.dumps(state)); return
    if "error" in operation:
        state.update(status="failed", error_code=operation["error"].get("code"))
        EVIDENCE.write_text(json.dumps(state, indent=2), encoding="utf-8")
        raise RuntimeError("App Distribution upload failed")
    release = operation["response"]["release"]
    call("PATCH", "/v1/" + release["name"], params={"updateMask": "release_notes.text"},
        json={"releaseNotes": {"text": "DG-309: 各回のTool操作・判断仮説・観測根拠・不確実性を表示。直近8回をメモリ保持。実機表示は未検証。"}})
    if args.tester:
        call("POST", "/v1/" + release["name"] + ":distribute", json={"testerEmails": [args.tester]})
    state.update(status="uploaded", release_name=release["name"],
        firebase_console_uri=release.get("firebaseConsoleUri"), testing_uri=release.get("testingUri"),
        tester_invited=bool(args.tester) or state.get("tester_invited", False), distribution_policy="invitation_only")
    EVIDENCE.write_text(json.dumps(state, indent=2), encoding="utf-8")
    print(json.dumps(state))
    # Firebase returns binaryDownloadUri, but direct links are prohibited here.

if __name__ == "__main__": main()
