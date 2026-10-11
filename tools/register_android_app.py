"""Register Android package and generate public Firebase resources."""
import base64
import json
import subprocess
import time
from pathlib import Path
from xml.etree.ElementTree import Element, SubElement, tostring
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
        reply = session.request(method, base + path, timeout=30, **kwargs)
        reply.raise_for_status()
        return reply.json()
    apps = call("GET", "projects/dopadopa/androidApps").get("apps", [])
    app = next((item for item in apps if item.get("packageName") == "com.example.dopadopa"), None)
    if not app:
        operation = call("POST", "projects/dopadopa/androidApps", json={"displayName": "DopaGuard Android", "packageName": "com.example.dopadopa"})
        for _ in range(20):
            if operation.get("done"): break
            time.sleep(1)
            operation = call("GET", operation["name"])
        if not operation.get("done") or operation.get("error"): raise RuntimeError("Android registration incomplete")
        app = operation["response"]
    config = json.loads(base64.b64decode(call("GET", app["name"] + "/config")["configFileContents"]))
    client = next(c for c in config["client"] if c["client_info"]["android_client_info"]["package_name"] == "com.example.dopadopa")
    root = Element("resources")
    values = {"google_app_id": client["client_info"]["mobilesdk_app_id"],
        "google_api_key": client["api_key"][0]["current_key"], "project_id": config["project_info"]["project_id"],
        "gcm_defaultSenderId": config["project_info"]["project_number"]}
    for name, value in values.items(): SubElement(root, "string", {"name": name, "translatable": "false"}).text = value
    Path("app/app/src/main/res/values/firebase.xml").write_bytes(tostring(root, encoding="utf-8", xml_declaration=True))
    print(json.dumps({"project": "dopadopa", "package": "com.example.dopadopa", "appId": app["appId"], "public_resources_saved": True}))

if __name__ == "__main__": main()
