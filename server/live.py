"""Immutable, expiring view used by existing read-only Agent tools."""
from datetime import datetime, timezone
from server.store import ConflictError, ExpiredSnapshotError

def read_snapshot(store, uid, snapshot_id, now=None):
    now = now or datetime.now(timezone.utc)
    value = store.get(uid, "snapshots", snapshot_id)
    if value["expires_at"] <= now:
        raise ExpiredSnapshotError("snapshot expired")
    if value["generation"] != store.generation(uid):
        raise ConflictError("snapshot generation changed")
    return value

class SnapshotStoreView:
    """A session ID resolves only to this snapshot; original session rows stay untouched."""
    def __init__(self, store, uid, snapshot_id):
        self.store, self.uid, self.snapshot_id = store, uid, snapshot_id
        self.snapshot = read_snapshot(store, uid, snapshot_id)

    def generation(self, uid):
        if uid != self.uid: raise PermissionError("foreign owner")
        return self.store.generation(uid)

    def get(self, uid, kind, key):
        if uid != self.uid or kind != "sessions" or key != self.snapshot["session_id"]:
            raise KeyError("record unavailable")
        value = read_snapshot(self.store, uid, self.snapshot_id)
        return {k: value[k] for k in ("session_id", "user_goal", "cloud_consent", "consent_revision", "events")}

    def list(self, uid, kind, limit=100):
        if uid != self.uid or kind != "feedback": raise PermissionError("tool scope")
        read_snapshot(self.store, uid, self.snapshot_id)
        return [r for r in self.store.list(uid, "feedback", limit)
            if r["session_id"] == self.snapshot["session_id"]]
