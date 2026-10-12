import unittest
from unittest.mock import patch
from fastapi.testclient import TestClient
from pydantic import ValidationError
from server.app import app, get_store
from server.auth import authenticated_user
from server.store import MemoryStore, RateLimitError
from server.onboarding import Input, Step, Turn, validate_step, budget
from google.adk.models.base_llm import BaseLlm
from google.adk.models.llm_response import LlmResponse
from google.genai import types

class ScriptedConsultant(BaseLlm):
    model: str = "scripted-consultation-test"
    received: str = ""
    async def generate_content_async(self, llm_request, stream=False):
        self.received = " ".join(p.text or "" for content in llm_request.contents for p in content.parts)
        yield LlmResponse(content=types.Content(role="model", parts=[types.Part.from_text(text=
            Step(message="音楽をどんな場面で楽しみたいですか？", choices=["作業の合間", "夜のくつろぎ"]).model_dump_json())]))

class ConsultationTest(unittest.TestCase):
    def test_actual_adk_receives_answers_and_deletes_transient_session(self):
        from server.onboarding import ConsultationAgent
        from server.tests.test_adk_agent import TrackingSessions
        import asyncio
        model = ScriptedConsultant()
        sessions = TrackingSessions()
        history = [Turn(question="何を楽しみますか？", choices=["音楽", "料理"], answer="音楽")]
        with patch("google.adk.sessions.InMemorySessionService", return_value=sessions):
            result = ConsultationAgent(model).run(history)
        self.assertIn("音楽", model.received)
        self.assertIn("相談履歴データ", model.received)
        self.assertEqual("音楽をどんな場面で楽しみたいですか？", result.message)
        self.assertEqual(1, sessions.deleted)
        self.assertEqual([], asyncio.run(sessions.list_sessions(app_name="usage_consultation", user_id="transient")).sessions)
    def setUp(self):
        self.store = MemoryStore()
        app.dependency_overrides[get_store] = lambda: self.store
        app.dependency_overrides[authenticated_user] = lambda: "alice"
        self.client = TestClient(app)
    def tearDown(self): app.dependency_overrides.clear()
    def test_consent_and_auth_are_required(self):
        self.assertEqual(422, self.client.post("/v1/usage-consultation", json={"consultation_consent": False}).status_code)
        del app.dependency_overrides[authenticated_user]
        self.assertEqual(401, self.client.post("/v1/usage-consultation", json={"consultation_consent": True}).status_code)
    def test_only_presented_choices_can_be_submitted(self):
        with self.assertRaises(ValidationError): Turn(question="何を楽しみますか？", choices=["音楽", "料理"], answer="other")
        with self.assertRaises(ValidationError): Input(consultation_consent=True, history=[dict(question="Q", choices=["a", "b"], answer="a")] * 5)
    def test_generated_question_is_returned_without_persisting_answers(self):
        history = [dict(question="好きなものは？", choices=["音楽", "料理"], answer="音楽")]
        step = Step(message="音楽を楽しむときはどちらが近いですか？", choices=["ライブ映像", "新しい曲探し"])
        with patch("server.onboarding.ConsultationAgent.run", return_value=step) as runner:
            result = self.client.post("/v1/usage-consultation", json={"consultation_consent": True, "history": history})
        self.assertEqual(200, result.status_code)
        self.assertEqual(step.message, result.json()["message"])
        self.assertEqual("音楽", runner.call_args.args[0][0].answer)
        self.assertEqual({}, self.store.rows)
        self.assertNotIn("音楽", str(self.store.live_limits))
    def test_provider_failure_does_not_leak_or_fake_a_question(self):
        with patch("server.onboarding.ConsultationAgent.run", side_effect=RuntimeError("sensitive detail")):
            result = self.client.post("/v1/usage-consultation", json={"consultation_consent": True})
        self.assertEqual(503, result.status_code)
        self.assertNotIn("sensitive", result.text)
        self.assertEqual(0, self.store.live_limits["alice"]["consultation_busy_until"])
    def test_fifth_turn_must_be_confirmation_and_earlier_turns_questions(self):
        with self.assertRaises(ValueError): validate_step(Step(message="質問", choices=["a", "a"]), [])
        with self.assertRaises(ValueError): validate_step(Step(message="完了", summary="推測した使い方"), [])
        with self.assertRaises(ValueError): validate_step(Step(message="追加質問", choices=["a", "b"]), [None] * 4)
        self.assertEqual("音楽を楽しむ", validate_step(Step(message="確認してください", summary="音楽を楽しむ"), [None] * 4).summary)
    def test_budget_is_shared_and_failed_starts_count(self):
        state = budget({}, 100, "one")
        with self.assertRaises(RateLimitError): budget(state, 101, "two")
        state["consultation_busy_until"] = 0
        state["consultation_starts"] = [100] * 30
        with self.assertRaises(RateLimitError): budget(state, 101, "two")
    def test_deletion_during_consultation_discards_summary(self):
        def deleted(history):
            self.store.delete_all("alice")
            return Step(message="確認", summary="古いまとめ")
        with patch("server.onboarding.ConsultationAgent.run", side_effect=deleted):
            result = self.client.post("/v1/usage-consultation", json={"consultation_consent": True})
        self.assertEqual(409, result.status_code)
