"""Owner-scoped stores. Local memory mode must be explicitly selected."""
from copy import deepcopy
from hashlib import sha256
from threading import RLock
from time import time

class ConflictError(ValueError): pass

class MemoryStore:
    def __init__(self):
        self.rows = {}
        self.lock = RLock()
        self.generations = {}

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
                "deletion_failed": False, "deletion_lease_until": time() + 120})
            return generation
        generation = begin(self.db.transaction())
        # A failed delete raises: never claim partial deletion is complete.
        # All writers read the same owner document transactionally, including other instances.
        try:
            for kind in ("feedback", "analyses", "sessions", "goals", "consents"):
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
                transaction.update(owner, {"deleting": False, "deletion_lease_until": 0})
        finish(self.db.transaction())
