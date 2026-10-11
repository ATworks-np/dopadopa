import unittest
from server.samples import sample_batch
from server.models import Analysis
from server.tests.test_agent import FakeClient
from server.agent import AgentRunner
from server.store import MemoryStore

class ScenarioTest(unittest.TestCase):
    def test_three_scenario_contracts_with_scripted_provider(self):
        # Results below are test fixtures, not Gemini output quality evidence.
        for scenario, alignment in (("learning-shorts", "possibly_diverged"), ("learning-only", "aligned"), ("unobservable", "unknown")):
            batch = sample_batch(scenario)
            result = Analysis(analysis_id="a1", session_id=batch.session_id, data_origin="sample",
                segments=[] if alignment == "unknown" else [dict(from_event_id="e1", to_event_id="e2", topic="学習の候補", intent_hypothesis="学習の可能性", evidence_ids=["e1", "e2"])],
                transitions=[dict(at_event_id="e3", hypothesis="別の閲覧行動に移った可能性", evidence_ids=["e2", "e3"])] if alignment == "possibly_diverged" else [],
                goal_alignment=alignment, uncertainty="意図・実際の視聴は不明。意図的な休憩の可能性もある。", user_confirmation_required=True)
            result.validate_evidence(batch.events)
            self.assertEqual(alignment, result.goal_alignment)
            self.assertTrue(all(e.data_origin == "sample" for e in batch.events))
    def test_sample_import_does_not_mutate_source(self):
        sample_batch("unobservable")
        self.assertEqual("available", sample_batch("learning-shorts").events[0].text_status)
