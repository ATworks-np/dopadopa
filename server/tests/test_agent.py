import unittest
from types import SimpleNamespace
from google.genai import types
from server.agent import ToolGateway, AgentRunner
from server.store import MemoryStore
from server.tests.test_models import event, analysis

class FakeClient:
    """Scripted provider: validates loop plumbing, never claimed as Gemini execution."""
    def __init__(self, result, repeated=False):
        self.step, self.result, self.repeated = 0, result, repeated
        self.models = self
    def generate_content(self, **kwargs):
        self.step += 1
        if self.step == 1 or self.repeated:
            calls = [types.FunctionCall(name=name, args={"session_id":"s1"}) for name in ("get_user_goals", "get_timeline", "get_click_events")]
            content = types.Content(role="model", parts=[types.Part(function_call=c) for c in calls])
            return SimpleNamespace(candidates=[SimpleNamespace(content=content)], function_calls=calls)
        if self.step == 2:
            return SimpleNamespace(candidates=[SimpleNamespace(content=types.Content(role="model", parts=[types.Part.from_text(text="調査完了")]))], function_calls=[])
        return SimpleNamespace(text=self.result.model_dump_json())

class AgentTest(unittest.TestCase):
    def setUp(self):
        self.store = MemoryStore()
        self.store.put("alice", "sessions", "s1", {"session_id":"s1", "user_goal":"学ぶ", "events":[event().model_dump(mode="json")]})
    def test_tool_cannot_access_other_user(self):
        with self.assertRaises(KeyError): ToolGateway(self.store, "bob", "s1").call("get_timeline", {"session_id":"s1"})
    def test_argument_and_session_validation(self):
        for args in ({"session_id":"s2"}, {"session_id":"s1", "limit":1000}, {"session_id":"s1", "user_id":"bob"}):
            with self.assertRaises((ValueError, PermissionError)): ToolGateway(self.store, "alice", "s1").call("get_timeline", args)
    def test_write_tool_prohibited(self):
        with self.assertRaises(ValueError): ToolGateway(self.store, "alice", "s1").call("save_behavior_feedback", {"session_id":"s1"})
    def test_loop_returns_validated_json(self):
        result, trace = AgentRunner(self.store, FakeClient(analysis()), "test-model").run("alice", "s1", "a1")
        self.assertEqual("unknown", result.goal_alignment)
        self.assertEqual(3, len(trace))
        self.assertNotIn("Kotlin", str(trace))
    def test_loop_is_bounded(self):
        with self.assertRaises(ValueError): AgentRunner(self.store, FakeClient(analysis(), repeated=True), "test-model").run("alice", "s1", "a1")
    def test_fabricated_provider_output_rejected(self):
        invalid = analysis(transitions=[dict(at_event_id="fake", hypothesis="候補", evidence_ids=["fake"])])
        with self.assertRaises(ValueError): AgentRunner(self.store, FakeClient(invalid), "test-model").run("alice", "s1", "a1")

if __name__ == "__main__": unittest.main()
