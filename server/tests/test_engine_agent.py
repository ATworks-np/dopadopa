import unittest
from types import SimpleNamespace
from unittest.mock import patch, MagicMock
from server.engine_agent import DopaGuardEngineAgent
from server.store import ConflictError

class EngineBoundaryTest(unittest.TestCase):
    def test_identity_is_verified_inside_runtime_and_client_supplied_uid_is_not_accepted(self):
        store = MagicMock()
        store.generation.return_value = 7
        result = SimpleNamespace(model_dump=lambda **_: {"sample": True})
        with patch.dict("os.environ", {"GOOGLE_CLOUD_PROJECT": "dopadopa"}), \
             patch("server.engine_agent.authenticated_user", return_value="verified-owner") as auth, \
             patch("server.engine_agent.FirestoreStore", return_value=store), \
             patch("server.engine_agent.AdkBehaviorRunner") as runner:
            runner.return_value.run.return_value = (result, [])
            self.assertEqual({"result": {"sample": True}, "trace": []},
                DopaGuardEngineAgent().query("fictional-token", "sample-session", "sample-analysis", 7.0))
            auth.assert_called_once_with("Bearer fictional-token")
            runner.return_value.run.assert_called_once_with("verified-owner", "sample-session", "sample-analysis")
            store.db.close.assert_called_once()
        with self.assertRaises(TypeError):
            DopaGuardEngineAgent().query("fictional-token", "s", "a", 7, uid="another-owner")

    def test_stale_generation_never_invokes_model_and_closes_store(self):
        store = MagicMock()
        store.generation.return_value = 8
        with patch.dict("os.environ", {"GOOGLE_CLOUD_PROJECT": "dopadopa"}), \
             patch("server.engine_agent.authenticated_user", return_value="verified-owner"), \
             patch("server.engine_agent.FirestoreStore", return_value=store), \
             patch("server.engine_agent.AdkBehaviorRunner") as runner:
            with self.assertRaises(ConflictError):
                DopaGuardEngineAgent().query("fictional-token", "s", "a", 7)
            runner.assert_not_called()
            store.db.close.assert_called_once()

    def test_invalid_arguments_rejected_before_authentication(self):
        with patch("server.engine_agent.authenticated_user") as auth:
            for sid, generation in [("../other", 0), ("s", -1), ("s", True), ("s", 0.5), ("s", float("nan")), ("s", 2**53)]:
                with self.assertRaises(ValueError):
                    DopaGuardEngineAgent().query("fictional-token", sid, "a", generation)
            auth.assert_not_called()
