import unittest
from pydantic import ValidationError
from server.models import Event, SessionBatch, Analysis

def event(**changes):
    value = dict(event_id="e1", session_id="s1", app_id="any-app", package_name="org.example",
        timestamp="2026-10-11T10:00:00+09:00", event_type="CLICK", text="Kotlin",
        text_status="available", source="clicked_node", observability="direct", data_origin="sample", consent_revision="v1")
    return Event.model_validate(value | changes)

def analysis(**changes):
    return Analysis.model_validate(dict(analysis_id="a1", session_id="s1", segments=[], transitions=[],
        goal_alignment="unknown", uncertainty="意図は不明", user_confirmation_required=True, data_origin="sample") | changes)

class ContractTest(unittest.TestCase):
    def test_dynamic_comment_requires_real_reference_and_click(self):
        segment = dict(from_event_id="e1", to_event_id="e1", topic="話題", intent_hypothesis="休憩の可能性", evidence_ids=["e1"])
        value = analysis(goal_alignment="possibly_diverged", segments=[segment],
            suggestion_comment="学習が目標ですが、別の話題の項目をクリックしています。休憩の可能性もあります。少し休みますか？",
            suggestion_evidence_ids=["e1"])
        self.assertIs(value.validate_evidence([event()]), value)
        with self.assertRaises(ValueError): value.model_copy(update={"suggestion_evidence_ids": ["fake"]}).validate_evidence([event()])
        with self.assertRaises(ValueError): value.model_copy(update={"suggestion_evidence_ids": []}).validate_evidence([event()])

    def test_comment_not_allowed_for_unknown_or_missing_click(self):
        with self.assertRaises(ValueError):
            analysis(suggestion_comment="休みますか？", suggestion_evidence_ids=["e1"]).validate_evidence([event()])
        with self.assertRaises(ValidationError): analysis(suggestion_comment="x" * 301)

    def test_old_result_can_be_read_without_generating_comment(self):
        value = analysis().validate_evidence([event()])
        self.assertIsNone(value.suggestion_comment)
        self.assertEqual([], value.suggestion_evidence_ids)
    def test_redacted_cannot_retain_text(self):
        with self.assertRaises(ValidationError): event(text_status="redacted")
    def test_timezone_required(self):
        with self.assertRaises(ValidationError): event(timestamp="2026-10-11T10:00:00")
    def test_consent_required(self):
        with self.assertRaises(ValidationError): SessionBatch(session_id="s1", user_goal="Learn", cloud_consent=False, consent_revision="v1", events=[event()])
    def test_origin_cannot_mix(self):
        with self.assertRaises(ValidationError): SessionBatch(session_id="s1", user_goal="Learn", cloud_consent=True, consent_revision="v1", events=[event(), event(event_id="e2", data_origin="real")])
    def test_fabricated_evidence_rejected(self):
        a = analysis(transitions=[dict(at_event_id="e1", hypothesis="変化の可能性", evidence_ids=["fake"])])
        with self.assertRaises(ValueError): a.validate_evidence([event()])
    def test_insufficient_data_unknown(self):
        missing = event(text=None, text_status="missing", source="none", observability="unavailable")
        self.assertEqual("unknown", analysis().validate_evidence([missing]).goal_alignment)
        with self.assertRaises(ValueError): analysis(goal_alignment="aligned").validate_evidence([missing])
    def test_provenance_mismatch(self):
        with self.assertRaises(ValueError): analysis(data_origin="real").validate_evidence([event()])

if __name__ == "__main__": unittest.main()
