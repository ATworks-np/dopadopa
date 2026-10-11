"""Build a minimal, credential-free Agent Engine staging package; no cloud mutation."""
import hashlib
import json
from pathlib import Path
import shutil
import cloudpickle
from server.engine_agent import DopaGuardEngineAgent

FILES = ("__init__.py", "engine_agent.py", "auth.py", "store.py", "adk_agent.py", "agent.py", "models.py")

def main():
    root = Path(__file__).resolve().parents[1]
    destination = root / "artifacts/private/engine-package/server"
    destination.mkdir(parents=True, exist_ok=True)
    # Exact allowlist: no Web assets, fixtures, local caches, tokens, or settings.
    for name in FILES:
        shutil.copyfile(root / "server" / name, destination / name)
    agent = cloudpickle.loads(cloudpickle.dumps(DopaGuardEngineAgent()))
    assert isinstance(agent, DopaGuardEngineAgent)
    assert vars(agent) == {}  # No clients, sessions, credentials, or user data serialized.
    manifest = {"status": "prepared", "deployed": False,
        "source_sha256": {name: hashlib.sha256((destination / name).read_bytes()).hexdigest() for name in FILES},
        "requirements_sha256": hashlib.sha256((root / "server/requirements.lock.txt").read_bytes()).hexdigest(),
        "serialized_agent_has_state": False,
        "pending": ["dedicated service account and runtime invocation IAM", "payload/token logging and tracing verification",
            "cloud deployment and resource ID", "Cloud Run remote caller", "sample inference and cleanup"]}
    (root / "artifacts/cloud/engine-package.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(json.dumps({"status": "prepared", "source_files": len(FILES), "deployed": False}))

if __name__ == "__main__": main()
