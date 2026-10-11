"""Inspect runtime logs in memory; persist only counts and boolean findings."""
import json
from pathlib import Path
import re
import subprocess
import requests
from server.samples import sample_batch

def main():
    root = Path(__file__).resolve().parents[1]
    resource = json.loads((root / "artifacts/cloud/engine-deployment.json").read_text())["resource_name"]
    token = subprocess.run([r"C:\Program Files (x86)\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd",
        "auth", "print-access-token", "--account=atomic1017@gmail.com"], capture_output=True, text=True, check=True).stdout.strip()
    headers = {"Authorization": "Bearer " + token}
    response = requests.get("https://asia-northeast1-aiplatform.googleapis.com/v1/" + resource, headers=headers, timeout=30)
    response.raise_for_status()
    variables = {row["name"]: row.get("value") for row in response.json()["spec"]["deploymentSpec"].get("env", [])}
    entries, cursor = [], None
    for _ in range(10):
        body = {"resourceNames": ["projects/dopadopa"],
            "filter": 'resource.type="aiplatform.googleapis.com/ReasoningEngine" AND resource.labels.reasoning_engine_id="' + resource.split('/')[-1] + '"',
            "pageSize": 1000}
        if cursor: body["pageToken"] = cursor
        response = requests.post("https://logging.googleapis.com/v2/entries:list", headers=headers, json=body, timeout=30)
        response.raise_for_status()
        page = response.json()
        entries.extend(page.get("entries", []))
        cursor = page.get("nextPageToken")
        if not cursor: break
    text = json.dumps(entries, ensure_ascii=False)
    sample = sample_batch("learning-only")
    markers = [event.text for event in sample.events if event.text] + [sample.user_goal, "fictional-invalid-token"]
    evidence = {"resource_name": resource, "entries_checked": len(entries), "pagination_complete": not cursor,
        "telemetry_disabled": variables.get("GOOGLE_CLOUD_AGENT_ENGINE_ENABLE_TELEMETRY") == "false",
        "content_capture_not_enabled": not variables.get("OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT"),
        "sample_content_found": any(marker in text for marker in markers),
        "jwt_pattern_found": bool(re.search(r"eyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}", text)),
        "scope": "all available runtime entries for this Engine; future output not guaranteed"}
    findings = {}
    for entry in entries:
        serialized = json.dumps(entry, ensure_ascii=False)
        if not (any(marker in serialized for marker in markers) or re.search(r"eyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}", serialized)):
            continue
        name = entry.get("logName", "unknown").split("/logs/")[-1]
        group = name + ":" + entry.get("severity", "DEFAULT")
        findings[group] = findings.get(group, 0) + 1
    evidence["finding_counts_by_log_and_severity"] = findings
    evidence["status"] = "passed" if (entries and not cursor and evidence["telemetry_disabled"] and evidence["content_capture_not_enabled"]
        and not evidence["sample_content_found"] and not evidence["jwt_pattern_found"]) else "inconclusive_or_failed"
    (root / "artifacts/cloud/engine-log-audit.json").write_text(json.dumps(evidence, indent=2), encoding="utf-8")
    print(json.dumps(evidence))
    if evidence["status"] != "passed": raise RuntimeError("Engine log audit not passed")

if __name__ == "__main__": main()
