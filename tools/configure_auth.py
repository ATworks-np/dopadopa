"""Configure email/password Firebase auth; OAuth tokens remain in memory."""
import json
import subprocess
from tools.cloud_cli import gcloud_executable
from pathlib import Path
import requests

def main():
    token = subprocess.run([
        gcloud_executable(),
        "auth", "print-access-token", "--account=atomic1017@gmail.com",
    ], capture_output=True, text=True, check=True).stdout.strip()
    session = requests.Session()
    session.headers.update({"Authorization": "Bearer " + token, "x-goog-user-project": "dopadopa"})
    url = "https://identitytoolkit.googleapis.com/admin/v2/projects/dopadopa/config"
    response = session.get(url, timeout=30)
    if response.status_code == 404:
        initialized = session.post("https://identitytoolkit.googleapis.com/v2/projects/dopadopa/identityPlatform:initializeAuth", json={}, timeout=30)
        if not initialized.ok:
            print(json.dumps({"stage": "initialize auth", "status": initialized.status_code, "error": initialized.json().get("error", {}).get("message")}))
            return
        response = session.get(url, timeout=30)
    if not response.ok:
        print(json.dumps({"stage": "read config", "status": response.status_code, "error": response.json().get("error", {}).get("message")}))
        return
    response = session.patch(url, params={"updateMask": "signIn.email.enabled,signIn.email.passwordRequired"},
        json={"name": "projects/dopadopa/config", "signIn": {"email": {"enabled": True, "passwordRequired": True}}}, timeout=30)
    if not response.ok:
        print(json.dumps({"stage": "enable email password", "status": response.status_code, "error": response.json().get("error", {}).get("message")}))
        return
    result = {"project": "dopadopa", "email": response.json().get("signIn", {}).get("email", {})}
    Path("artifacts/cloud/firebase-auth.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result))

if __name__ == "__main__": main()
