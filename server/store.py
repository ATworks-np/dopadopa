"""Owner-scoped stores. Local memory mode must be explicitly selected."""
from copy import deepcopy
from hashlib import sha256
from threading import RLock
from time import time
from datetime import datetime, timezone

class ConflictError(ValueError): pass
class ExpiredSnapshotError(ValueError): pass
class RateLimitError(ValueError): pass

def reserve_live_budget(state, snapshot_id, now):
    starts = [at for at in state.get("live_starts", []) if now - at < 3600]
    if state.get("live_busy_until", 0) > now:
        raise RateLimitError("automatic analysis already running")
    if len(starts) >= 60 or (starts and now - starts[-1] < 60):
        raise RateLimitError("automatic analysis budget exhausted")
    return {"live_starts": starts + [now], "live_busy_until": now + 70,
        "live_busy_snapshot": snapshot_id}

def same_snapshot(existing, value):
    return {k: v for k, v in existing.items() if k not in {"received_at", "expires_at"}} == \
        {k: v for k, v in value.items() if k not in {"received_at", "expires_at"}}

class MemoryStore:
    def __init__(self):
        self.rows = {}
        self.lock = RLock()
        self.generations = {}
        self.live_limits = {}

    def register_snapshot(self, uid, value, generation):
        with self.lock:
            if generation != self.generation(uid): raise ConflictError("generation changed")
            key = (uid, "snapshots", value["snapshot_id"])
            old = self.rows.get(key)
            if old is not None:
                if not same_snapshot(old, value): raise ConflictError("immutable snapshot")
                if old["expires_at"] <= datetime.now(timezone.utc): raise ExpiredSnapshotError("snapshot expired")
                return deepcopy(old)
            self.rows[key] = deepcopy(value)
            return deepcopy(value)

    def claim_live_analysis(self, uid, snapshot_id, record, generation, now):
        with self.lock:
            if generation != self.generation(uid): raise ConflictError("generation changed")
            snapshot = self.get(uid, "snapshots", snapshot_id)
            if snapshot["expires_at"].timestamp() <= now: raise ExpiredSnapshotError("snapshot expired")
            address = (uid, "live_analyses", snapshot_id)
            old = self.rows.get(address)
            if old is not None:
                if old["status"] == "running": raise RateLimitError("analysis already running")
                return deepcopy(old)
            self.live_limits[uid] = reserve_live_budget(self.live_limits.get(uid, {}), snapshot_id, now)
            self.rows[address] = deepcopy(record)
            return None

    def finish_live_analysis(self, uid, snapshot_id, record, generation):
        with self.lock:
            self.put(uid, "live_analyses", snapshot_id, record, expected_generation=generation)
            state = self.live_limits.get(uid, {})
            if state.get("live_busy_snapshot") == snapshot_id: state["live_busy_until"] = 0

    def prune_live(self, uid, now):
        with self.lock:
            self.rows = {k: v for k, v in self.rows.items() if not (k[0] == uid and
                k[1] in {"snapshots", "live_analyses", "live_actions"} and v["expires_at"] <= now)}

    def generation(self, uid):
        with self.lock: return self.generations.get(uid, 0)

    def put(self, uid, kind, key, value, immutable=False, expected_generation=None):
        with self.lock:
            if expected_generation is not None and expected_generation != self.generation(uid):
                raise ConflictError("data deleted while operation was running")
            address = (uid, kind, key)
            if immutable and address in self.rows and self.rows[address] != value:
                raise ConflictError("existing record cannot be overwritten")
            self.rows[address] = deepcopy(value)

    def get(self, uid, kind, key):
        with self.lock:
            value = self.rows.get((uid, kind, key))
            if value is None: raise KeyError(key)
            return deepcopy(value)

    def list(self, uid, kind, limit=100):
        with self.lock:
            return deepcopy([v for (owner, category, _), v in self.rows.items() if owner == uid and category == kind][:limit])

    def delete_all(self, uid):
        with self.lock:
            self.generations[uid] = self.generation(uid) + 1
            self.rows = {k: v for k, v in self.rows.items() if k[0] != uid}
            self.live_limits.setdefault(uid, {})["live_busy_until"] = 0

class FirestoreStore:
    def __init__(self, project):
        from google.cloud import firestore
        self.db = firestore.Client(project=project)

    def collection(self, uid, kind):
        owner = sha256(uid.encode()).hexdigest()
        return self.db.collection("dopaguard_users").document(owner).collection(kind)

    def owner_ref(self, uid):
        return self.db.collection("dopaguard_users").document(sha256(uid.encode()).hexdigest())

    def generation(self, uid):
        state = self.owner_ref(uid).get(timeout=10).to_dict() or {}
        if state.get("deleting", False): raise ConflictError("data deletion in progress")
        return state.get("generation", 0)

    def register_snapshot(self, uid, value, generation):
        from google.cloud import firestore
        owner = self.owner_ref(uid)
        ref = self.collection(uid, "snapshots").document(value["snapshot_id"])
        @firestore.transactional
        def write(transaction):
            state = owner.get(transaction=transaction).to_dict() or {}
            existing = ref.get(transaction=transaction)
            if state.get("deleting") or state.get("generation", 0) != generation:
                raise ConflictError("generation changed")
            if existing.exists:
                old = existing.to_dict()
                if not same_snapshot(old, value): raise ConflictError("immutable snapshot")
                if old["expires_at"] <= datetime.now(timezone.utc): raise ExpiredSnapshotError("snapshot expired")
                return old
            transaction.set(ref, value)
            return value
        return write(self.db.transaction())

    def claim_live_analysis(self, uid, snapshot_id, record, generation, now):
        from google.cloud import firestore
        owner = self.owner_ref(uid)
        source = self.collection(uid, "snapshots").document(snapshot_id)
        ref = self.collection(uid, "live_analyses").document(snapshot_id)
        @firestore.transactional
        def claim(transaction):
            state = owner.get(transaction=transaction).to_dict() or {}
            snapshot = source.get(transaction=transaction)
            existing = ref.get(transaction=transaction)
            if state.get("deleting") or state.get("generation", 0) != generation:
                raise ConflictError("generation changed")
            if not snapshot.exists: raise KeyError("snapshot unavailable")
            if snapshot.to_dict()["expires_at"].timestamp() <= now: raise ExpiredSnapshotError("snapshot expired")
            if existing.exists:
                old = existing.to_dict()
                if old["status"] == "running": raise RateLimitError("analysis already running")
                return old
            budget = reserve_live_budget(state, snapshot_id, now)
            transaction.set(owner, budget, merge=True)
            transaction.set(ref, record)
            return None
        return claim(self.db.transaction())

    def finish_live_analysis(self, uid, snapshot_id, record, generation):
        from google.cloud import firestore
        owner = self.owner_ref(uid)
        ref = self.collection(uid, "live_analyses").document(snapshot_id)
        @firestore.transactional
        def finish(transaction):
            state = owner.get(transaction=transaction).to_dict() or {}
            if state.get("deleting") or state.get("generation", 0) != generation:
                raise ConflictError("generation changed")
            transaction.set(ref, record)
            if state.get("live_busy_snapshot") == snapshot_id:
                transaction.update(owner, {"live_busy_until": 0})
        finish(self.db.transaction())

    def prune_live(self, uid, now):
        from google.cloud.firestore_v1.base_query import FieldFilter
        for kind in ("snapshots", "live_analyses", "live_actions"):
            for doc in self.collection(uid, kind).where(filter=FieldFilter("expires_at", "<=", now)).limit(100).stream(timeout=10):
                from google.cloud import firestore
                @firestore.transactional
                def remove(transaction):
                    current = doc.reference.get(transaction=transaction)
                    if current.exists and current.to_dict()["expires_at"] <= now:
                        transaction.delete(doc.reference)
                remove(self.db.transaction())

    def put(self, uid, kind, key, value, immutable=False, expected_generation=None):
        from google.cloud import firestore
        ref = self.collection(uid, kind).document(key)
        owner = self.owner_ref(uid)
        @firestore.transactional
        def write(transaction):
            state = owner.get(transaction=transaction).to_dict() or {}
            if state.get("deleting", False) or (expected_generation is not None and state.get("generation", 0) != expected_generation):
                raise ConflictError("data deleted while operation was running")
            existing = ref.get(transaction=transaction)
            if immutable and existing.exists:
                if existing.to_dict() != value: raise ConflictError("existing record cannot be overwritten")
                return
            transaction.set(ref, value)
        write(self.db.transaction())

    def get(self, uid, kind, key):
        result = self.collection(uid, kind).document(key).get(timeout=10)
        if not result.exists: raise KeyError(key)
        return result.to_dict()

    def list(self, uid, kind, limit=100):
        return [r.to_dict() for r in self.collection(uid, kind).limit(limit).stream(timeout=10)]

    def delete_all(self, uid):
        from google.cloud import firestore
        owner = self.owner_ref(uid)
        @firestore.transactional
        def begin(transaction):
            state = owner.get(transaction=transaction).to_dict() or {}
            if (state.get("deleting", False) and not state.get("deletion_failed", False)
                    and state.get("deletion_lease_until", 0) > time()):
                raise ConflictError("data deletion in progress")
            generation = state.get("generation", 0) + 1
            transaction.set(owner, {"generation": generation, "deleting": True,
                "deletion_failed": False, "deletion_lease_until": time() + 120}, merge=True)
            return generation
        generation = begin(self.db.transaction())
        # A failed delete raises: never claim partial deletion is complete.
        # All writers read the same owner document transactionally, including other instances.
        try:
            for kind in ("live_actions", "live_analyses", "snapshots", "feedback", "analyses", "sessions", "goals", "consents"):
                for doc in self.collection(uid, kind).stream(timeout=10):
                    @firestore.transactional
                    def remove(transaction):
                        state = owner.get(transaction=transaction).to_dict() or {}
                        if state.get("generation") != generation or not state.get("deleting"):
                            raise ConflictError("deletion superseded")
                        transaction.update(owner, {"deletion_lease_until": time() + 120})
                        transaction.delete(doc.reference)
                    remove(self.db.transaction())
        except Exception:
            # Keep writes disabled, while allowing an explicit deletion retry.
            @firestore.transactional
            def fail(transaction):
                state = owner.get(transaction=transaction).to_dict() or {}
                if state.get("generation") == generation:
                    transaction.update(owner, {"deletion_failed": True})
            fail(self.db.transaction())
            raise
        @firestore.transactional
        def finish(transaction):
            state = owner.get(transaction=transaction).to_dict() or {}
            if state.get("generation") == generation:
                transaction.update(owner, {"deleting": False, "deletion_lease_until": 0, "live_busy_until": 0})
        finish(self.db.transaction())
