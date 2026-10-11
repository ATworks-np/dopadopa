import unittest
from unittest.mock import patch
from fastapi.testclient import TestClient
from server.app import app, get_store
from server.auth import authenticated_user
from server.store import MemoryStore
from server.tests.test_models import event

class ApiTest(unittest.TestCase):
    def setUp(self):
        self.store = MemoryStore()
        app.dependency_overrides[get_store] = lambda: self.store
        app.dependency_overrides[authenticated_user] = lambda: "alice"
        self.client = TestClient(app)
        self.batch = dict(session_id="s1", user_goal="学ぶ", cloud_consent=True, consent_revision="v1", events=[event().model_dump(mode="json")])
    def tearDown(self): app.dependency_overrides.clear()
    def test_auth_required(self):
        del app.dependency_overrides[authenticated_user]
        self.assertEqual(401, self.client.get("/v1/sessions").status_code)
    def test_owner_isolation(self):
        self.assertEqual(200, self.client.post("/v1/sessions:batch", json=self.batch).status_code)
        app.dependency_overrides[authenticated_user] = lambda: "bob"
        self.assertEqual(404, self.client.get("/v1/sessions/s1").status_code)
        self.assertEqual([], self.client.get("/v1/sessions").json())

    def test_saved_analysis_listing_is_session_and_owner_scoped(self):
        self.client.post('/v1/sessions:batch', json=self.batch)
        self.store.put('alice','analyses','a1', {'analysis_id':'a1','session_id':'s1','status':'completed','data_origin':'sample','result':{'private':'content'}})
        self.store.put('alice','analyses','a2', {'analysis_id':'a2','session_id':'s2','status':'completed'})
        self.store.put('bob','analyses','b1', {'analysis_id':'b1','session_id':'s1','status':'completed'})
        rows = self.client.get('/v1/sessions/s1/analyses').json()
        self.assertEqual(['a1'], [r['analysis_id'] for r in rows])
        self.assertNotIn('result', rows[0])
        app.dependency_overrides[authenticated_user] = lambda:'bob'
        self.assertEqual(404,self.client.get('/v1/sessions/s1/analyses').status_code)
        app.dependency_overrides.clear()
        self.assertEqual(401,self.client.get('/v1/sessions/s1/analyses').status_code)
    def test_idempotent_and_conflict(self):
        for _ in range(2): self.assertEqual(200, self.client.post("/v1/sessions:batch", json=self.batch).status_code)
        self.batch["events"][0]["text"] = "different"
        self.assertEqual(409, self.client.post("/v1/sessions:batch", json=self.batch).status_code)
    def test_no_consent_no_write(self):
        self.batch["cloud_consent"] = False
        self.assertEqual(422, self.client.post("/v1/sessions:batch", json=self.batch).status_code)
        self.assertEqual([], self.store.list("alice", "sessions"))
    def test_delete_only_self(self):
        self.store.put("bob", "sessions", "s2", {"session_id": "s2"})
        self.client.post("/v1/sessions:batch", json=self.batch)
        self.assertTrue(self.client.delete("/v1/user/data").json()["deleted"])
        self.assertEqual([], self.store.list("alice", "sessions"))
        self.assertEqual(1, len(self.store.list("bob", "sessions")))
    def test_feedback_does_not_change_evidence(self):
        self.client.post("/v1/sessions:batch", json=self.batch)
        self.store.put("alice", "analyses", "a1", {"analysis_id":"a1", "session_id":"s1", "status":"completed"})
        before = self.store.get("alice", "sessions", "s1")
        response = self.client.post("/v1/analyses/a1/feedback", json={"verdict":"incorrect", "corrected_intent":"休憩"})
        self.assertEqual(200, response.status_code)
        self.assertEqual(before, self.store.get("alice", "sessions", "s1"))
        self.assertEqual("incorrect", self.client.get("/v1/analyses/a1/feedback").json()[0]["verdict"])
    def test_request_limit(self):
        self.assertEqual(413, self.client.post("/v1/sessions:batch", content=b"x" * 300001).status_code)

    def test_deletion_during_analysis_cannot_restore_result(self):
        self.client.post("/v1/sessions:batch", json=self.batch)
        def delete_during_run(*args):
            self.store.delete_all("alice")
            raise RuntimeError("operation cancelled")
        with patch("server.agent.AgentRunner.run", side_effect=delete_during_run):
            response = self.client.post("/v1/analyses", json={"session_id":"s1"})
        self.assertEqual(409, response.status_code)
        self.assertEqual([], self.store.list("alice", "analyses"))
        self.assertEqual([], self.store.list("alice", "sessions"))

    def test_stale_writer_rejected_but_new_registration_allowed(self):
        from server.store import ConflictError
        generation = self.store.generation("alice")
        self.store.delete_all("alice")
        with self.assertRaises(ConflictError):
            self.store.put("alice", "feedback", "f1", {}, expected_generation=generation)
        self.assertEqual(409, self.client.post("/v1/sessions:batch", json=self.batch).status_code)
        current=self.client.get('/v1/user/generation').json()['generation']
        self.assertEqual(200, self.client.post("/v1/sessions:batch", json=self.batch,
            headers={'X-DopaGuard-Generation':str(current)}).status_code)

    def test_batch_dispatched_before_delete_but_arriving_after_cannot_restore_data(self):
        old=self.client.get('/v1/user/generation').json()['generation']
        self.assertEqual(200,self.client.delete('/v1/user/data').status_code)
        # The batch was formed before deletion but first reaches the API afterwards.
        response=self.client.post('/v1/sessions:batch',json=self.batch,
            headers={'X-DopaGuard-Generation':str(old)})
        self.assertEqual(409,response.status_code)
        self.assertEqual([],self.store.list('alice','sessions'))
        self.assertEqual(409,self.client.post('/v1/demo/sessions/learning-only/import',
            headers={'X-DopaGuard-Generation':str(old)}).status_code)
        self.assertEqual(422,self.client.post('/v1/sessions:batch',json=self.batch,
            headers={'X-DopaGuard-Generation':'-1'}).status_code)
