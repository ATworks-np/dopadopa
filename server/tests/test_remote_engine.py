import json
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import MagicMock, patch
from server.remote_engine import RemoteEngineRunner
from server.store import MemoryStore
from server.samples import sample_batch

class RemoteEngineTest(unittest.TestCase):
    def test_bounds_transport_and_revalidates_returned_identity(self):
        batch = sample_batch("learning-only").model_dump(mode="json")
        store = MemoryStore()
        store.put("owner", "sessions", batch["session_id"], batch)
        result = json.loads(Path("artifacts/cloud/gemini-smoke.json").read_text(encoding="utf-8"))["records"][1]["analysis"]
        result["analysis_id"] = "a-test"
        output = {"result": result, "trace": [{"tool": "get_timeline", "count": 2.0, "elapsed_ms": 5}]}
        response = MagicMock(status_code=200)
        response.__enter__.return_value = response
        response.iter_content.return_value = [json.dumps({"output": output}).encode()]
        credentials = SimpleNamespace(token="fictional-service-token", refresh=lambda request: None)
        env = {"AGENT_ENGINE_RESOURCE": "projects/463106527119/locations/asia-northeast1/reasoningEngines/1"}
        with patch.dict("os.environ", env), patch("server.remote_engine.google.auth.default", return_value=(credentials, None)), \
             patch("server.remote_engine.requests.post", return_value=response) as post:
            runner = RemoteEngineRunner(store, "Bearer fictional-user-token")
            self.assertEqual("a-test", runner.run("owner", batch["session_id"], "a-test")[0].analysis_id)
            self.assertFalse(post.call_args.kwargs["allow_redirects"])
            self.assertEqual(20, post.call_args.kwargs["timeout"][0])
            self.assertTrue(109 <= post.call_args.kwargs["timeout"][1] <= 110)
            self.assertNotIn("uid", post.call_args.kwargs["json"]["input"])
            rejected = MagicMock(status_code=503)
            rejected.__enter__.return_value = rejected
            post.reset_mock()
            response.iter_content.return_value = [json.dumps({"output": output}).encode()]
            post.side_effect = [rejected, response]
            with patch("server.remote_engine.time.sleep"):
                self.assertEqual("a-test", runner.run("owner", batch["session_id"], "a-test")[0].analysis_id)
            self.assertEqual(2, post.call_count)
            self.assertEqual(200, runner.engine_http_status)
            post.side_effect = None
            output["result"]["analysis_id"] = "another-analysis"
            response.iter_content.return_value = [json.dumps({"output": output}).encode()]
            with self.assertRaises(ValueError): runner.run("owner", batch["session_id"], "a-test")

    def test_retries_transient_http_once_but_not_auth_rejection(self):
        batch = sample_batch("learning-only").model_dump(mode="json")
        store = MemoryStore()
        store.put("owner", "sessions", batch["session_id"], batch)
        credentials = SimpleNamespace(token="fictional", refresh=lambda request: None)
        env = {"AGENT_ENGINE_RESOURCE": "projects/463106527119/locations/asia-northeast1/reasoningEngines/1"}
        for code, count in [(503, 2), (429, 2), (403, 1), (400, 1), (500, 1)]:
            response = MagicMock(status_code=code)
            response.__enter__.return_value = response
            with patch.dict("os.environ", env), patch("server.remote_engine.google.auth.default", return_value=(credentials, None)), \
                 patch("server.remote_engine.requests.post", return_value=response) as post, patch("server.remote_engine.time.sleep"):
                runner = RemoteEngineRunner(store, "Bearer fictional")
                with self.assertRaises(RuntimeError): runner.run("owner", batch["session_id"], "a-test")
                self.assertEqual(count, post.call_count)
                self.assertEqual(code, runner.engine_http_status)
                self.assertEqual("engine_http_rejection", runner.failure_stage)

    def test_deleted_generation_prevents_retry(self):
        batch = sample_batch("learning-only").model_dump(mode="json")
        store = MemoryStore()
        store.put("owner", "sessions", batch["session_id"], batch)
        credentials = SimpleNamespace(token="fictional", refresh=lambda request: None)
        response = MagicMock(status_code=503)
        response.__enter__.return_value = response
        env = {"AGENT_ENGINE_RESOURCE": "projects/463106527119/locations/asia-northeast1/reasoningEngines/1"}
        from server.store import ConflictError
        with patch.dict("os.environ", env), patch("server.remote_engine.google.auth.default", return_value=(credentials, None)), \
             patch("server.remote_engine.requests.post", return_value=response) as post, \
             patch("server.remote_engine.time.sleep", side_effect=lambda _: store.delete_all("owner")):
            with self.assertRaises(ConflictError):
                RemoteEngineRunner(store, "Bearer fictional").run("owner", batch["session_id"], "a-test")
            self.assertEqual(1, post.call_count)

    def test_rejects_unconfigured_external_resource_before_network(self):
        with patch.dict("os.environ", {"AGENT_ENGINE_RESOURCE": "https://untrusted.invalid"}), \
             patch("server.remote_engine.requests.post") as post:
            with self.assertRaises(ValueError): RemoteEngineRunner(MemoryStore(), "Bearer fictional").run("owner", "s", "a")
            post.assert_not_called()
