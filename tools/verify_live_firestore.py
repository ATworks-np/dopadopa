"""Real Firestore checks with an isolated synthetic owner and sample events."""
import json
import subprocess
from datetime import datetime, timezone, timedelta
from pathlib import Path
from uuid import uuid4
from google.oauth2.credentials import Credentials
from google.cloud import firestore
from server.store import FirestoreStore, ConflictError, RateLimitError, ExpiredSnapshotError
from server.live import read_snapshot
from server.tests.test_live import batch
from tools.cloud_cli import gcloud_executable

def main():
    uid = "qa-live-" + str(uuid4())
    token = subprocess.run([gcloud_executable(), "auth", "print-access-token", "--account=atomic1017@gmail.com"],
        capture_output=True, text=True, check=True).stdout.strip()
    store = FirestoreStore.__new__(FirestoreStore)
    store.db = firestore.Client(project="dopadopa", credentials=Credentials(token=token))
    checks = []
    record = {"data_origin": "sample", "fixture_source": "synthetic", "checks": checks, "cleanup": False}
    try:
        from server.models import LiveSnapshotInput
        value = LiveSnapshotInput.model_validate(batch()).model_dump(mode="json")
        now = datetime.now(timezone.utc)
        value.update(received_at=now, expires_at=now + timedelta(hours=24))
        first = store.register_snapshot(uid, value, 0)
        again = store.register_snapshot(uid, value | {"expires_at": now + timedelta(hours=25)}, 0)
        assert first["expires_at"] == again["expires_at"]
        try: store.register_snapshot(uid, value | {"user_goal": "different"}, 0)
        except ConflictError: pass
        else: raise AssertionError("snapshot mutation accepted")
        checks.append("immutable replay without expiry extension")
        running = {"status": "running", "expires_at": value["expires_at"]}
        assert store.claim_live_analysis(uid, "snap-1", running, 0, now.timestamp()) is None
        try: store.claim_live_analysis(uid, "snap-1", running, 0, now.timestamp())
        except RateLimitError: pass
        else: raise AssertionError("overlapping execution accepted")
        finished = running | {"status": "failed"}
        store.finish_live_analysis(uid, "snap-1", finished, 0)
        assert store.claim_live_analysis(uid, "snap-1", running, 0, now.timestamp()) == finished
        checks.append("transactional single execution and cached terminal replay")
        store.register_snapshot(uid, value | {"snapshot_id": "snap-2"}, 0)
        try: store.claim_live_analysis(uid, "snap-2", running, 0, now.timestamp())
        except RateLimitError: pass
        else: raise AssertionError("minimum interval bypassed")
        checks.append("failure consumes user budget and interval")
        store.register_snapshot(uid, value | {"snapshot_id": "expired", "expires_at": now - timedelta(seconds=1)}, 0)
        try: read_snapshot(store, uid, "expired")
        except ExpiredSnapshotError: pass
        else: raise AssertionError("expired observation exposed")
        store.prune_live(uid, now)
        try: store.get(uid, "snapshots", "expired")
        except KeyError: pass
        else: raise AssertionError("expired observation retained")
        checks.append("expired reads rejected and content pruned")
        store.delete_all(uid)
        assert store.generation(uid) == 1
        assert not store.list(uid, "snapshots") and not store.list(uid, "live_analyses")
        try: store.register_snapshot(uid, value, 0)
        except ConflictError: pass
        else: raise AssertionError("old deletion generation accepted")
        state = store.owner_ref(uid).get().to_dict()
        assert len(state["live_starts"]) == 1
        checks.append("deletion removes content and keeps anti-abuse budget")
        record["status"] = "PASS"
    except Exception as error:
        record.update(status="FAIL", error_type=type(error).__name__)
        raise
    finally:
        try:
            store.delete_all(uid)
            assert not store.list(uid, "snapshots") and not store.list(uid, "live_analyses")
            store.owner_ref(uid).delete(timeout=10)
            record["cleanup"] = True
        finally:
            store.db.close()
            Path("artifacts/cloud/live-firestore-smoke.json").write_text(json.dumps(record, indent=2))
            print(json.dumps(record))

if __name__ == "__main__": main()
