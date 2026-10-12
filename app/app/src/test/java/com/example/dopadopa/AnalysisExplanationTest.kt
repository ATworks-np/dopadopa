package com.example.dopadopa

import com.example.dopadopa.intervention.AnalysisExplanation as E
import org.junit.Assert.*
import org.junit.Test

class AnalysisExplanationTest {
    @Test fun separates_tool_actions_hypothesis_and_observed_evidence() {
        val text = E.render("学習", "possibly_diverged", listOf(E.Tool("get_click_events", 2, 41)),
            listOf(E.Claim("娯楽へ移った可能性", listOf("e1"))),
            listOf(E.Observation("e1", "12:30:01", "CLICK", "架空タイトル", "available")), "視聴したとは分かりません")
        assertTrue(text.contains("クリック観測 / 2件 / 41ms"))
        assertTrue(text.contains("仮説: 娯楽へ移った可能性"))
        assertTrue(text.contains("観測 12:30:01 CLICK: 架空タイトル"))
        assertTrue(text.contains("不確実性: 視聴したとは分かりません"))
    }
    @Test fun never_displays_redacted_text_or_invents_missing_evidence() {
        val text = E.render("goal", "unknown", emptyList(), listOf(E.Claim("仮説", listOf("e", "absent"))),
            listOf(E.Observation("e", "00:00", "CLICK", "SECRET", "redacted")), "不明")
        assertFalse(text.contains("SECRET"))
        assertTrue(text.contains("機密除外"))
        assertTrue(text.contains("根拠観測を確認できません"))
    }
    @Test fun unknown_without_claims_is_explicit_and_output_is_bounded() {
        val text = E.render("a".repeat(10000), "unknown", emptyList(), emptyList(), emptyList(), "b".repeat(10000))
        assertTrue(text.contains("観測からは判断できない"))
        assertTrue(text.contains("根拠付きの区間・転換はありません"))
        assertTrue(text.length < 1500)
    }
}
