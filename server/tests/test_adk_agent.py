import unittest
import asyncio
from unittest.mock import patch
from typing import Any
from google.adk.sessions import InMemorySessionService
from google.adk.models.base_llm import BaseLlm
from google.adk.models.llm_response import LlmResponse
from google.genai import types
from server.adk_agent import AdkBehaviorRunner, ProviderAnalysis
from server.store import MemoryStore
from server.tests.test_models import event, analysis

class ScriptedModel(BaseLlm):
    model: str = "scripted-adk-test"
    step: int = 0
    omit_tools: bool = False
    fabricate: bool = False
    omit_confirmation: bool = False

    async def generate_content_async(self, llm_request, stream=False):
        self.step += 1
        if self.step == 1 and not self.omit_tools:
            parts = [types.Part.from_function_call(name=name, args={"session_id":"s1"})
                for name in ("get_user_goals", "get_timeline", "get_click_events")]
        elif self.step == 2 or self.omit_tools:
            parts = [types.Part.from_text(text="調査済み。意図は不明。")]
        else:
            result = analysis()
            if self.fabricate:
                result = analysis(transitions=[dict(at_event_id="fake", hypothesis="候補", evidence_ids=["fake"])])
            raw = result.model_dump()
            if self.omit_confirmation: raw["user_confirmation_required"] = False
            import json
            parts = [types.Part.from_text(text=json.dumps(raw))]
        yield LlmResponse(content=types.Content(role="model", parts=parts))

class AdkTest(unittest.TestCase):
    def setUp(self):
        self.store = MemoryStore()
        self.store.put("alice", "sessions", "s1", {"user_goal":"学ぶ", "events":[event().model_dump(mode="json")]})
    def test_actual_adk_runner_executes_scoped_tools(self):
        result, trace = AdkBehaviorRunner(self.store, ScriptedModel()).run("alice", "s1", "a1")
        self.assertEqual("unknown", result.goal_alignment)
        self.assertEqual(3, len(trace))
        self.assertNotIn("Kotlin", str(trace))
    def test_adk_rejects_missing_required_investigation(self):
        with self.assertRaises(ValueError): AdkBehaviorRunner(self.store, ScriptedModel(omit_tools=True)).run("alice", "s1", "a1")
    def test_adk_rejects_fabricated_evidence(self):
        with self.assertRaises(ValueError): AdkBehaviorRunner(self.store, ScriptedModel(fabricate=True)).run("alice", "s1", "a1")
    def test_other_person_cannot_start_investigation(self):
        with self.assertRaises(KeyError): AdkBehaviorRunner(self.store, ScriptedModel()).run("bob", "s1", "a1")
    def test_provider_schema_keeps_confirmation_boolean(self):
        field = ProviderAnalysis.model_json_schema()["properties"]["user_confirmation_required"]
        self.assertEqual("boolean", field["type"])
        self.assertNotIn("const", field)
        import json
        schema = json.dumps(ProviderAnalysis.model_json_schema())
        for unsupported in ("additionalProperties", "minLength", "maxLength", "minItems", "maxItems"):
            self.assertNotIn(unsupported, schema)
    def test_final_validation_rejects_confirmation_false(self):
        with self.assertRaises(ValueError):
            AdkBehaviorRunner(self.store, ScriptedModel(omit_confirmation=True)).run("alice", "s1", "a1")

    def test_deadline_cancels_model_and_removes_transient_session(self):
        model = WaitingModel()
        service = TrackingSessions()
        with patch('google.adk.sessions.InMemorySessionService', return_value=service):
            with self.assertRaises(TimeoutError):
                AdkBehaviorRunner(self.store, model, timeout_seconds=0.05).run('alice', 's1', 'a1')
        self.assertTrue(model.cancelled)
        self.assertEqual(1, service.deleted)
        self.assertEqual([], asyncio.run(service.list_sessions(app_name='dopaguard', user_id='alice')).sessions)

    def test_actual_adk_model_call_limit_terminates_loop_and_cleans_session(self):
        from google.adk.agents.invocation_context import LlmCallsLimitExceededError
        model = LoopingModel()
        service = TrackingSessions()
        with patch('google.adk.sessions.InMemorySessionService', return_value=service):
            with self.assertRaises(LlmCallsLimitExceededError):
                AdkBehaviorRunner(self.store, model).run('alice', 's1', 'a1')
        self.assertEqual(8, model.step)
        self.assertEqual(1, service.deleted)
        self.assertEqual([], asyncio.run(service.list_sessions(app_name='dopaguard', user_id='alice')).sessions)

    def test_actual_adk_tool_limit_stops_parallel_burst(self):
        service = TrackingSessions()
        runner = AdkBehaviorRunner(self.store, BurstModel())
        with patch('google.adk.sessions.InMemorySessionService', return_value=service):
            with self.assertRaisesRegex(ValueError, 'tool call limit'):
                runner.run('alice', 's1', 'a1')
        self.assertLessEqual(len(runner.trace), 12)
        self.assertEqual(1, service.deleted)
        self.assertEqual([], asyncio.run(service.list_sessions(app_name='dopaguard', user_id='alice')).sessions)

    def test_deletion_after_investigation_rejects_result_and_cleans_session(self):
        service = TrackingSessions()
        with patch('google.adk.sessions.InMemorySessionService', return_value=service):
            with self.assertRaisesRegex(PermissionError, 'data deleted'):
                AdkBehaviorRunner(self.store, DeletingModel(store=self.store)).run('alice', 's1', 'a1')
        self.assertEqual([], self.store.list('alice', 'sessions'))
        self.assertEqual([], self.store.list('alice', 'analyses'))
        self.assertEqual(1, service.deleted)

class BurstModel(BaseLlm):
    model: str = 'tool-burst-test'
    async def generate_content_async(self, llm_request, stream=False):
        yield LlmResponse(content=types.Content(role='model', parts=[
            types.Part.from_function_call(name='get_timeline', args={'session_id':'s1'}) for _ in range(13)]))

class DeletingModel(ScriptedModel):
    store: Any
    async def generate_content_async(self, llm_request, stream=False):
        # Delete after the first tool response, while the model is still running.
        if self.step == 1: self.store.delete_all('alice')
        async for response in super().generate_content_async(llm_request, stream):
            yield response

class WaitingModel(BaseLlm):
    model: str = 'waiting-model-test'
    cancelled: bool = False
    async def generate_content_async(self, llm_request, stream=False):
        try:
            await asyncio.Event().wait()
            yield LlmResponse()
        except asyncio.CancelledError:
            self.cancelled = True
            raise

class LoopingModel(BaseLlm):
    model: str = 'looping-model-test'
    step: int = 0
    async def generate_content_async(self, llm_request, stream=False):
        self.step += 1
        yield LlmResponse(content=types.Content(role='model', parts=[
            types.Part.from_function_call(name='get_timeline', args={'session_id':'s1'})]))

class TrackingSessions(InMemorySessionService):
    def __init__(self):
        super().__init__()
        self.deleted = 0
    async def delete_session(self, **kwargs):
        await super().delete_session(**kwargs)
        self.deleted += 1
