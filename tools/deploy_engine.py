"""Deploy the minimal stateless package. OAuth exists only in process memory."""
import json
import os
from pathlib import Path
from unittest.mock import patch
import subprocess
import vertexai
from google.oauth2.credentials import Credentials
from vertexai import agent_engines
from server.engine_agent import DopaGuardEngineAgent
from tools.prepare_engine import main as prepare

def main():
    root = Path(__file__).resolve().parents[1]
    evidence = root / "artifacts/cloud/engine-deployment.json"
    if evidence.exists():
        previous = json.loads(evidence.read_text(encoding="utf-8"))
        if previous.get("resource_name"):
            raise RuntimeError("existing engine recorded; inspect before creating another")
    prepare()
    token = subprocess.run([r"C:\Program Files (x86)\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd",
        "auth", "print-access-token", "--account=atomic1017@gmail.com"],
        capture_output=True, text=True, check=True).stdout.strip()
    env = {"DOPAGUARD_PROJECT": "dopadopa", "DOPAGUARD_MODEL_LOCATION": "global",
        "GOOGLE_GENAI_USE_VERTEXAI": "true", "GEMINI_MODEL": "gemini-2.5-flash",
        "GOOGLE_CLOUD_AGENT_ENGINE_ENABLE_TELEMETRY": "false"}
    credentials = Credentials(token=token)
    vertexai.init(project="dopadopa", location="asia-northeast1",
        staging_bucket="gs://dopadopa-agent-staging", credentials=credentials)
    requirements = (root / "server/requirements.lock.txt").read_text(encoding="utf-8").splitlines()
    original = Path.cwd()
    state = {"status": "creating", "service_account": "dopaguard-engine@dopadopa.iam.gserviceaccount.com",
        "location": "asia-northeast1", "telemetry_enabled": False, "verified_inference": False}
    evidence.write_text(json.dumps(state, indent=2), encoding="utf-8")
    try:
        os.chdir(root / "artifacts/private/engine-package")
        # The SDK's staging storage client independently calls google.auth.default.
        # Bind it to the same explicitly selected account without writing ADC.
        with patch("google.auth.default", return_value=(credentials, "dopadopa")):
            engine = agent_engines.create(DopaGuardEngineAgent(), requirements=requirements,
                extra_packages=["server"], display_name="dopadopa-behavior",
                service_account=state["service_account"], env_vars=env,
                min_instances=0, max_instances=2, container_concurrency=2,
                resource_limits={"cpu": "1", "memory": "1Gi"})
        state.update(status="deployed", resource_name=engine.resource_name)
    except Exception as error:
        state.update(status="failed", error_type=type(error).__name__)
        raise
    finally:
        os.chdir(original)
        evidence.write_text(json.dumps(state, indent=2), encoding="utf-8")
    print(json.dumps(state))

if __name__ == "__main__": main()
