"""Run actual Vertex Gemini against explicitly fictional scenarios."""
import json
import subprocess
from tools.cloud_cli import gcloud_executable
import traceback
from pathlib import Path
from google import genai
from google.oauth2.credentials import Credentials
from google.adk.models.google_llm import Gemini
from server.adk_agent import AdkBehaviorRunner
from server.samples import SCENARIOS, sample_batch
from server.store import MemoryStore

def main():
    token = subprocess.run([
        gcloud_executable(),
        "auth", "print-access-token", "--account=atomic1017@gmail.com",
    ], capture_output=True, text=True, check=True).stdout.strip()
    records = []
    for scenario in SCENARIOS:
        client = genai.Client(vertexai=True, project="dopadopa", location="global",
            credentials=Credentials(token=token), http_options={"timeout": 30000})
        try:
            batch = sample_batch(scenario)
            store = MemoryStore()
            store.put("sample-verification", "sessions", batch.session_id, batch.model_dump(mode="json"))
            runner = AdkBehaviorRunner(store, Gemini(model="gemini-2.5-flash", client=client))
            try:
                result, trace = runner.run("sample-verification", batch.session_id, "verify-" + scenario)
                records.append({"scenario": scenario, "status": "completed", "analysis": result.model_dump(mode="json"), "trace": trace})
            except Exception as error:
                # Provider messages may contain source data; only classify the error.
                reasons = {"model identity mismatch", "required evidence tools were not called", "fabricated evidence", "fabricated transition", "reversed segment", "evidence outside segment", "insufficient observations must remain unknown", "alignment requires supporting segments"}
                reason = str(error) if type(error) is ValueError and str(error) in reasons else "provider or schema failure"
                frames = [{"file": Path(frame.filename).name, "line": frame.lineno, "function": frame.name} for frame in traceback.extract_tb(error.__traceback__)[-5:]]
                # This script sends fictional sample text only. Keep diagnostic messages
                # locally, with the credential removed, and never print them as API logs.
                diagnostic = str(error).replace(token, "[credential removed]")[:2000]
                records.append({"scenario": scenario, "status": "failed", "error_type": type(error).__name__, "reason": reason, "diagnostic": diagnostic, "frames": frames, "trace": runner.trace})
            path = Path("artifacts/cloud/gemini-smoke.json")
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps({"project": "dopadopa", "model": "gemini-2.5-flash", "data_origin": "sample", "records": records}, ensure_ascii=False, indent=2), encoding="utf-8")
            print(json.dumps({"scenario": scenario, "status": records[-1]["status"], "tools": len(runner.trace)}, ensure_ascii=False), flush=True)
        finally:
            client.close()

if __name__ == "__main__": main()
