"""Register the Firebase web app and save public client configuration only."""
import json
import subprocess
import time
from pathlib import Path
import requests

def main():
    token = subprocess.run([
        r"C:\Program Files (x86)\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd",
        "auth", "print-access-token", "--account=atomic1017@gmail.com",
    ], capture_output=True, text=True, check=True).stdout.strip()
    session = requests.Session()
    session.headers.update({"Authorization": "Bearer " + token, "x-goog-user-project": "dopadopa"})
    base = "https://firebase.googleapis.com/v1beta1/"
    def call(method, path, **kwargs):
        response = session.request(method, base + path, timeout=30, **kwargs)
        response.raise_for_status()
        return response.json()
    apps = call("GET", "projects/dopadopa/webApps").get("apps", [])
    app = next((item for item in apps if item.get("displayName") == "DopaGuard Web"), None)
    if not app:
        operation = call("POST", "projects/dopadopa/webApps", json={"displayName": "DopaGuard Web"})
        for _ in range(20):
            if operation.get("done"): break
            time.sleep(1)
            operation = call("GET", operation["name"])
        if not operation.get("done") or operation.get("error"):
            raise RuntimeError("Firebase web app operation incomplete")
        app = operation["response"]
    config = call("GET", app["name"] + "/config")
    Path("artifacts/cloud/firebase-web-config.json").write_text(json.dumps(config, indent=2), encoding="utf-8")
    print(json.dumps({"project": "dopadopa", "appId": app["appId"], "public_config_saved": True}))

if __name__ == "__main__": main()
