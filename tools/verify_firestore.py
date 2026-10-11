"""Exercise the actual store with fictional, isolated records; no saved tokens."""
import json
import subprocess
from tools.cloud_cli import gcloud_executable
from pathlib import Path
from uuid import uuid4
from time import time
from unittest.mock import patch
from google.oauth2.credentials import Credentials
from google.cloud import firestore
from server.store import FirestoreStore, ConflictError

def main():
    token = subprocess.run([
        gcloud_executable(),
        "auth", "print-access-token", "--account=atomic1017@gmail.com",
    ], capture_output=True, text=True, check=True).stdout.strip()
    store = FirestoreStore.__new__(FirestoreStore)
    store.db = firestore.Client(project="dopadopa", credentials=Credentials(token=token))
    owner, other = "smoke-" + str(uuid4()), "smoke-" + str(uuid4())
    checks = []
    try:
        generation = store.generation(owner)
        value = {"data_origin": "sample", "events": [], "user_goal": "fictional smoke test"}
        store.put(owner, "sessions", "sample", value, immutable=True, expected_generation=generation)
        store.put(owner, "sessions", "sample", value, immutable=True, expected_generation=generation)
        assert store.get(owner, "sessions", "sample") == value
        checks.append("transactional registration and idempotent resend")
        try: store.get(other, "sessions", "sample")
        except KeyError: checks.append("other owner cannot read record")
        else: raise AssertionError("owner isolation failed")
        try: store.put(owner, "sessions", "sample", {"changed": True}, immutable=True)
        except ConflictError: checks.append("immutable conflict rejected")
        else: raise AssertionError("immutable conflict accepted")
        store.delete_all(owner)
        assert store.list(owner, "sessions") == []
        try: store.put(owner, "analyses", "stale", {}, expected_generation=generation)
        except ConflictError: checks.append("post-deletion stale write rejected")
        else: raise AssertionError("stale write accepted")
        store.put(owner, "sessions", "new", value, expected_generation=store.generation(owner))
        checks.append("new generation permits registration")
        # Simulate a process that died without setting deletion_failed.
        store.owner_ref(owner).update({"deleting": True, "deletion_failed": False,
            "deletion_lease_until": time() - 1}, timeout=10)
        store.delete_all(owner)
        assert store.list(owner, "sessions") == []
        checks.append("expired crashed deletion recovered")
        store.put(owner, "sessions", "failure-fixture", value, expected_generation=store.generation(owner))
        with patch.object(store, "collection", side_effect=RuntimeError("fictional deletion transport failure")):
            try: store.delete_all(owner)
            except RuntimeError: pass
            else: raise AssertionError("deletion failure hidden")
        state = store.owner_ref(owner).get(timeout=10).to_dict()
        assert state["deleting"] and state["deletion_failed"]
        try: store.generation(owner)
        except ConflictError: pass
        else: raise AssertionError("write generation exposed during failed deletion")
        store.delete_all(owner)
        assert store.list(owner, "sessions") == []
        checks.append("failed deletion keeps writes disabled and retry completes")
    finally:
        store.delete_all(owner)
        store.owner_ref(owner).delete(timeout=10)
        store.db.close()
    result = {"project": "dopadopa", "data_origin": "sample", "checks": checks, "cleaned_up": True}
    evidence = Path("artifacts/cloud/firestore-smoke.json")
    evidence.parent.mkdir(parents=True, exist_ok=True)
    evidence.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))

if __name__ == "__main__": main()
