"""Synthetic/sample fixtures only; no evidence of real-device observation."""
import copy
import unittest
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone, timedelta
from unittest.mock import patch
from fastapi.testclient import TestClient
from server.app import app, get_store
from server.auth import authenticated_user
from server.models import Analysis, Event
from server.store import MemoryStore, RateLimitError, ConflictError, reserve_live_budget
from server.live import SnapshotStoreView

def batch(snapshot_id="snap-1"):
    now = datetime.now(timezone.utc)
    return {"snapshot_id": snapshot_id, "session_id": "live-session", "user_goal": "学習を続ける",
        "cloud_consent": True, "collection_consent": True, "automatic_consent": True,
        "consent_revision": "c1", "goal_revision": "g1", "generation": 0, "context_sequence": 2,
        "consent_since": (now - timedelta(seconds=40)).isoformat(), "captured_at": now.isoformat(),
        "events": [{"event_id": "e" + str(i), "session_id": "live-session", "app_id": "youtube",
            "package_name": "com.google.android.youtube", "timestamp": (now - timedelta(seconds=5-i)).isoformat(),
            "event_type": "CLICK", "text": "架空の学習動画", "text_status": "available",
            "source": "clicked_node", "observability": "direct", "data_origin": "sample",
            "consent_revision": "c1"} for i in range(2)]}

def fake_analysis(uid, sid, aid):
    result = Analysis(analysis_id=aid, session_id=sid, segments=[], transitions=[], goal_alignment="unknown",
        uncertainty="架空試験", user_confirmation_required=True, data_origin="sample")
    trace = [{"tool": name, "count": 1, "elapsed_ms": 1}
        for name in ("get_user_goals", "get_timeline", "get_content_events")]
    return result, trace

class LiveApiTest(unittest.TestCase):
    def setUp(self):
        self.store = MemoryStore()
        app.dependency_overrides[get_store] = lambda: self.store
        app.dependency_overrides[authenticated_user] = lambda: "alice"
        self.client = TestClient(app)
        self.body = batch()
    def tearDown(self): app.dependency_overrides.clear()
    def register(self):
        response = self.client.post("/v1/live/snapshots", json=self.body)
        self.assertEqual(200, response.status_code)
        return response.json()
    def analyze(self): return self.client.post("/v1/live/snapshots/snap-1/analyze")

    def test_snapshot_does_not_mutate_old_session_and_replay_does_not_extend_expiry(self):
        self.store.put("alice", "sessions", "live-session", {"original": "unchanged"})
        a = self.register(); b = self.register()
        self.assertEqual(a["expires_at"], b["expires_at"])
        self.assertEqual({"original": "unchanged"}, self.store.get("alice", "sessions", "live-session"))
        self.body["events"][0]["text"] = "別の原文"
        self.assertEqual(409, self.client.post("/v1/live/snapshots", json=self.body).status_code)

    def test_owner_scope_and_authentication(self):
        self.register()
        app.dependency_overrides[authenticated_user] = lambda: "bob"
        self.assertEqual(404, self.client.get("/v1/live/snapshots/snap-1").status_code)
        self.assertEqual(404, self.analyze().status_code)
        del app.dependency_overrides[authenticated_user]
        self.assertEqual(401, self.client.get("/v1/live/snapshots/snap-1").status_code)

    def test_all_consent_and_observation_boundaries(self):
        invalid = []
        for key in ("cloud_consent", "collection_consent", "automatic_consent"):
            invalid.append(self.body | {key: False})
        invalid.extend([self.body | {"context_sequence": True}, self.body | {"generation": -1},
            self.body | {"owner": "other"}, self.body | {"events": self.body["events"] * 51}])
        old = copy.deepcopy(self.body)
        old["events"][0]["timestamp"] = (datetime.now(timezone.utc) - timedelta(days=1)).isoformat()
        invalid.append(old)
        for value in invalid:
            self.assertEqual(422, self.client.post("/v1/live/snapshots", json=value).status_code)
        self.assertEqual([], self.store.list("alice", "snapshots"))

    def test_freshness_and_expiry(self):
        old = self.body | {"captured_at": (datetime.now(timezone.utc) - timedelta(seconds=61)).isoformat(),
            "events": [self.body["events"][0] | {"timestamp": (datetime.now(timezone.utc)-timedelta(seconds=65)).isoformat()}],
            "consent_since": (datetime.now(timezone.utc)-timedelta(seconds=100)).isoformat()}
        self.assertEqual(422, self.client.post("/v1/live/snapshots", json=old).status_code)
        self.register()
        value = self.store.get("alice", "snapshots", "snap-1")
        value["expires_at"] = datetime.now(timezone.utc) - timedelta(seconds=1)
        self.store.put("alice", "snapshots", "snap-1", value)
        self.assertEqual(410, self.client.get("/v1/live/snapshots/snap-1").status_code)
        self.assertEqual(410, self.analyze().status_code)
        self.store.prune_live("alice", datetime.now(timezone.utc))
        self.assertEqual([], self.store.list("alice", "snapshots"))

    def test_snapshot_tool_view_cannot_read_other_sessions_or_owner(self):
        self.register(); view = SnapshotStoreView(self.store, "alice", "snap-1")
        actual = view.get("alice", "sessions", "live-session")["events"]
        self.assertEqual([Event.model_validate(r) for r in self.body["events"]], [Event.model_validate(r) for r in actual])
        with self.assertRaises(KeyError): view.get("alice", "sessions", "different")
        with self.assertRaises(KeyError): view.get("bob", "sessions", "live-session")
        self.store.delete_all("alice")
        with self.assertRaises(KeyError): view.get("alice", "sessions", "live-session")

    def test_analysis_is_bound_and_idempotent_and_sample_stays_sample(self):
        self.register()
        with patch("server.agent.AgentRunner.run", side_effect=fake_analysis) as run:
            one = self.analyze(); two = self.analyze()
        self.assertEqual(200, one.status_code)
        self.assertEqual(one.json(), two.json())
        self.assertEqual(1, run.call_count)
        self.assertEqual("snap-1", one.json()["snapshot_id"])
        self.assertEqual("sample", one.json()["data_origin"])
        self.assertTrue(one.json()["evidence_validated"])

    def test_missing_tools_fabricated_evidence_and_provider_detail_fail_safely(self):
        def invalid(uid, sid, aid):
            result, trace = fake_analysis(uid, sid, aid)
            result.segments = [dict(from_event_id="fake", to_event_id="fake", topic="fake",
                intent_hypothesis="fake", evidence_ids=["fake"])]
            return result, trace
        for effect in (RuntimeError("sensitive-provider-detail"), invalid,
            lambda *args: (fake_analysis(*args)[0], [])):
            self.store = MemoryStore(); self.register()
            with patch("server.agent.AgentRunner.run", side_effect=effect):
                value = self.analyze().json()
            self.assertEqual("failed", value["status"])
            self.assertNotIn("sensitive-provider-detail", str(value))
            self.assertEqual([], value["trace"])
            self.assertFalse(value["evidence_validated"])

    def test_deletion_during_analysis_does_not_resurrect_snapshot_or_result(self):
        self.register()
        def remove(*args):
            self.store.delete_all("alice")
            return fake_analysis(*args)
        with patch("server.agent.AgentRunner.run", side_effect=remove):
            self.assertEqual(409, self.analyze().status_code)
        self.assertEqual([], self.store.list("alice", "live_analyses"))
        self.assertEqual(409, self.client.post("/v1/live/snapshots", json=self.body).status_code)

    def test_failed_analyses_count_against_server_budget(self):
        self.register()
        with patch("server.agent.AgentRunner.run", side_effect=RuntimeError("failed")):
            self.assertEqual("failed", self.analyze().json()["status"])
        self.body["snapshot_id"] = "snap-2"
        self.register()
        self.assertEqual(429, self.client.post("/v1/live/snapshots/snap-2/analyze").status_code)

    def test_concurrent_claims_have_single_winner(self):
        self.register(); source = self.store.get("alice", "snapshots", "snap-1")
        def claim(i):
            try:
                self.store.claim_live_analysis("alice", "snap-1", {"status": "running", "expires_at": source["expires_at"]},
                    0, datetime.now(timezone.utc).timestamp())
                return True
            except RateLimitError: return False
        with ThreadPoolExecutor(max_workers=8) as pool:
            self.assertEqual(1, sum(pool.map(claim, range(8))))

    def test_live_limits_survive_deletion_and_owner_isolation(self):
        state = {}
        for i in range(60):
            state = reserve_live_budget(state | {"live_busy_until": 0}, str(i), i * 60)
        with self.assertRaises(RateLimitError): reserve_live_budget(state | {"live_busy_until": 0}, "next", 3599)
        self.assertEqual(60, len(state["live_starts"]))
        self.assertEqual(1, len(reserve_live_budget(state | {"live_busy_until": 0}, "next", 7200)["live_starts"]))
        self.store.live_limits["alice"] = state
        self.store.delete_all("alice")
        self.assertEqual(60, len(self.store.live_limits["alice"]["live_starts"]))
        self.assertNotIn("bob", self.store.live_limits)

    def test_live_failure_log_drops_provider_text(self):
        self.register()
        secret = "secret observation 架空の学習動画 eyJaaaaaaaaaaaaaaa.bbbbbbbbbbbbbbbb.cccccccccccc"
        with patch.dict("os.environ", {"AGENT_BACKEND": "engine"}), patch("server.remote_engine.RemoteEngineRunner") as runner, \
             self.assertLogs("dopaguard", level="WARNING") as logs:
            runner.return_value.failure_stage = secret
            runner.return_value.run.side_effect = RuntimeError(secret)
            response = self.analyze()
        body = response.json()
        self.assertEqual("failed", body["status"])
        self.assertEqual("unknown", body["diagnostic_stage"])
        self.assertNotIn(secret, str(body))
        self.assertIn("WARNING:dopaguard:live_analysis_failure stage=unknown http_status=None", logs.output)
        self.assertFalse(any(secret in line for line in logs.output))

    def test_live_failure_log_keeps_fixed_transport_stage(self):
        self.register()
        secret = "provider said the observation text"
        with patch.dict("os.environ", {"AGENT_BACKEND": "engine"}), patch("server.remote_engine.RemoteEngineRunner") as runner, \
             self.assertLogs("dopaguard", level="WARNING") as logs:
            runner.return_value.failure_stage = "engine_transport"
            runner.return_value.run.side_effect = RuntimeError(secret)
            response = self.analyze()
        body = response.json()
        self.assertEqual("engine_transport", body["diagnostic_stage"])
        self.assertNotIn(secret, str(body))
        self.assertIn("WARNING:dopaguard:live_analysis_failure stage=engine_transport http_status=None", logs.output)
        self.assertFalse(any(secret in line for line in logs.output))

    def test_engine_path_binds_snapshot_without_accepting_owner_from_model(self):
        self.register()
        with patch.dict("os.environ", {"AGENT_BACKEND": "engine"}), patch("server.remote_engine.RemoteEngineRunner") as runner:
            runner.return_value.run.side_effect = fake_analysis
            # Handle the extra binding argument explicitly in this mock.
            runner.return_value.run.side_effect = lambda uid, sid, aid, **kw: fake_analysis(uid, sid, aid)
            response = self.analyze()
        self.assertEqual(200, response.status_code)
        self.assertTrue(response.json()["evidence_validated"])
        self.assertEqual({"snapshot_id": "snap-1"}, runner.return_value.run.call_args.kwargs)
