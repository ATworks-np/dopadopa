"""Exercise production deletion control flow with transactional Firestore doubles."""
import unittest
from types import SimpleNamespace
from unittest.mock import patch
from server.store import ConflictError, FirestoreStore

class DeletionRecoveryTest(unittest.TestCase):
    def make_store(self, state, before_stream=lambda: None):
        owner = SimpleNamespace(get=lambda **_: SimpleNamespace(to_dict=lambda: dict(state)))
        deleted = []
        transaction = SimpleNamespace(
            set=lambda ref, value, merge=False: (None if merge else state.clear(), state.update(value)),
            update=lambda ref, value: state.update(value),
            delete=lambda ref: deleted.append(ref))
        store = FirestoreStore.__new__(FirestoreStore)
        store.db = SimpleNamespace(transaction=lambda: transaction)
        store.owner_ref = lambda uid: owner
        def stream(**kwargs):
            before_stream()
            return [SimpleNamespace(reference="fictional-record")]
        store.collection = lambda uid, kind: SimpleNamespace(stream=stream)
        return store, deleted

    @patch("google.cloud.firestore.transactional", side_effect=lambda fn: fn)
    @patch("server.store.time", return_value=1000)
    def test_expired_crashed_delete_can_be_retried(self, *_):
        state = {"generation": 4, "deleting": True, "deletion_lease_until": 999, "live_starts": [900]}
        store, deleted = self.make_store(state)
        store.delete_all("fictional-owner")
        self.assertEqual(5, state["generation"])
        self.assertFalse(state["deleting"])
        self.assertEqual(8, len(deleted))
        self.assertEqual([900], state["live_starts"])
        self.assertEqual(0, state["live_busy_until"])

    @patch("google.cloud.firestore.transactional", side_effect=lambda fn: fn)
    @patch("server.store.time", return_value=1000)
    def test_live_delete_rejects_duplicate(self, *_):
        state = {"generation": 4, "deleting": True, "deletion_lease_until": 1100}
        store, deleted = self.make_store(state)
        with self.assertRaises(ConflictError): store.delete_all("fictional-owner")
        self.assertEqual([], deleted)

    @patch("google.cloud.firestore.transactional", side_effect=lambda fn: fn)
    @patch("server.store.time", return_value=1000)
    def test_superseded_worker_cannot_delete_or_mark_new_generation_failed(self, *_):
        state = {"generation": 4}
        def takeover(): state.update(generation=6, deleting=False, deletion_failed=False)
        store, deleted = self.make_store(state, takeover)
        with self.assertRaises(ConflictError): store.delete_all("fictional-owner")
        self.assertEqual([], deleted)
        self.assertEqual(6, state["generation"])
        self.assertFalse(state["deletion_failed"])
