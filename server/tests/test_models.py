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
    def chain_events(self):
        return [event(event_id=f"e{i}", text=title, event_type="WINDOW_CHANGE", source="parent_context",
            observability="inferred", timestamp=f"2026-10-11T10:00:{i * 10:02d}+09:00")
            for i, title in enumerate(("数学の面白い問題", "別解の裏技", "驚きの数学雑学"), 1)]

    def test_aligned_content_chain_can_support_rest_suggestion(self):
        events = self.chain_events()
        result = analysis(goal_alignment="aligned", usage_pattern="possible_chaining",
            usage_pattern_evidence_ids=["e1", "e2", "e3"],
            segments=[dict(from_event_id="e1", to_event_id="e3", topic="数学", intent_hypothesis="学習の可能性", evidence_ids=["e1", "e2", "e3"])],
            suggestion_comment="数学のタイトルが次々に表示されています。一区切りにしますか？",
            suggestion_reason="学習の目標には合う内容ですが、連続した切り替えが観測されています。意図的な調べ物の可能性もあります。",
            suggestion_evidence_ids=["e1", "e2", "e3"])
        self.assertIs(result, result.validate_evidence(events))
        self.assertEqual("aligned", result.goal_alignment)

    def test_chain_cannot_be_fabricated_or_count_duplicate_titles(self):
        result = analysis(usage_pattern="possible_chaining", usage_pattern_evidence_ids=["e1", "e2", "e3"])
        events = self.chain_events()
        for invalid in (events[:2], [e.model_copy(update={"text":"同じタイトル"}) for e in events],
                [e.model_copy(update={"event_type":"SCROLL"}) for e in events]):
            with self.assertRaises(ValueError): result.validate_evidence(invalid)
        with self.assertRaises(ValueError): result.model_copy(update={"usage_pattern_evidence_ids":["fake", "e2", "e3"]}).validate_evidence(events)
        with self.assertRaises(ValueError): analysis(usage_pattern_evidence_ids=["e1"]).validate_evidence(events)

    def test_chaining_suggestion_requires_the_whole_chain_evidence(self):
        result = analysis(usage_pattern="possible_chaining", usage_pattern_evidence_ids=["e1", "e2", "e3"],
            suggestion_comment="休みますか？", suggestion_evidence_ids=["e1"])
        with self.assertRaises(ValueError): result.validate_evidence(self.chain_events())

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
        self.assertIsNone(value.suggestion_reason)

    def test_natural_language_reason_requires_comment_and_bounded_length(self):
        with self.assertRaises(ValueError): analysis(suggestion_reason="クリックから判断しました。").validate_evidence([event()])
        with self.assertRaises(ValidationError): analysis(suggestion_reason="x" * 1201)

    def test_natural_language_reason_round_trip(self):
        reason = "目標は学習です。Kotlinの項目をクリックしたことを観測しました。目的が変わった瞬間は、この情報だけでは確認できません。"
        value = analysis(goal_alignment="possibly_diverged", suggestion_comment="少し休みますか？",
            suggestion_reason=reason, suggestion_evidence_ids=["e1"],
            segments=[dict(from_event_id="e1", to_event_id="e1", topic="候補", intent_hypothesis="仮説", evidence_ids=["e1"])])
        value.validate_evidence([event()])
        self.assertEqual(reason, Analysis.model_validate_json(value.model_dump_json()).suggestion_reason)
    def test_screen_title_without_click_supports_alignment_and_suggestion(self):
        title = event(event_type="WINDOW_CHANGE", source="parent_context", observability="inferred")
        value = analysis(goal_alignment="possibly_diverged", suggestion_comment="別のタイトルが表示されました。休みますか？",
            suggestion_reason="表示されたタイトルに基づく仮説です。実際の視聴は未確認です。", suggestion_evidence_ids=["e1"],
            segments=[dict(from_event_id="e1", to_event_id="e1", topic="候補", intent_hypothesis="仮説", evidence_ids=["e1"])])
        self.assertIs(value.validate_evidence([title]), value)
        for kind in ("SCROLL", "APP_FOREGROUND", "APP_BACKGROUND"):
            with self.assertRaises(ValueError): value.validate_evidence([event(event_type=kind)])
        with self.assertRaises(ValueError):
            value.validate_evidence([event(event_type="WINDOW_CHANGE", text=None, text_status="missing", source="none", observability="unavailable")])

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
