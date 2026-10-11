"""Enroll the user-authorized dopadopa project using explicit gcloud identity.

Access tokens stay in memory and are never printed or saved.
"""
import json
import subprocess
from pathlib import Path
import requests

BASE = "https://firebase.googleapis.com/v1beta1/"
PROJECT = "dopadopa"
ACCOUNT = "atomic1017@gmail.com"
EVIDENCE = Path("artifacts/cloud/firebase-enrollment.json")

def main():
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--operation")
    args = parser.parse_args()
    token = subprocess.run([
        r"C:\Program Files (x86)\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd",
        "auth", "print-access-token", "--account=" + ACCOUNT,
    ], capture_output=True, text=True, check=True).stdout.strip()
    session = requests.Session()
    session.headers["Authorization"] = "Bearer " + token
    session.headers["x-goog-user-project"] = PROJECT
    def call(method, path, **kwargs):
        response = session.request(method, BASE + path, timeout=30, **kwargs)
        if response.status_code == 404: return None
        if not response.ok:
            error = response.json().get("error", {})
            raise RuntimeError(f"Firebase request failed: HTTP {response.status_code}; {error.get('message', 'unknown error')}")
        return response.json()
    if args.operation:
        if not args.operation.startswith("operations/") or ".." in args.operation:
            raise ValueError("invalid operation")
        result = call("GET", args.operation)
        if result and result.get("error"): raise RuntimeError(result["error"].get("message", "enrollment failed"))
        if result and result.get("done"):
            result = call("GET", "projects/" + PROJECT)
    else:
        result = call("GET", "projects/" + PROJECT)
        if result is None: result = call("POST", "projects/" + PROJECT + ":addFirebase", json={})
    EVIDENCE.parent.mkdir(parents=True, exist_ok=True)
    EVIDENCE.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))

if __name__ == "__main__": main()
