package com.example.dopadopa

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.dopadopa.ui.AnalysisView
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test

class AnalysisViewTest {
    @get:Rule val compose = createComposeRule()
    private val timeline = JSONObject("""{"events":[{"event_id":"e1","timestamp":"2026-10-11T00:00:00Z","event_type":"CLICK","text":"Kotlinの基本","text_status":"available","source":"clicked_node","observability":"direct"}]}""")
    @Test fun hypothesisOpensObservedEvidence() {
        val record = JSONObject("""{"status":"completed","data_origin":"sample","result":{"goal_alignment":"aligned","uncertainty":"実際の視聴は不明","segments":[{"from_event_id":"e1","to_event_id":"e1","topic":"開発","intent_hypothesis":"学習の可能性","evidence_ids":["e1"]}],"transitions":[]},"trace":[]}""")
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { AnalysisView(record, timeline) } } }
        compose.onNodeWithText("AIの仮説です。本人の確認が必要です。").assertExists()
        compose.onNodeWithText("不確実性: 実際の視聴は不明").assertExists()
        compose.onNodeWithText("根拠を見る · e1").performScrollTo().performClick()
        compose.onNodeWithText("取得した文言: Kotlinの基本").assertIsDisplayed()
        compose.onNodeWithText("text_status: available").assertIsDisplayed()
    }
    @Test fun insufficientObservationsStayUnknown() {
        val record = JSONObject("""{"status":"insufficient_data","data_origin":"sample","result":{"goal_alignment":"unknown","uncertainty":"取得できる文言なし","segments":[],"transitions":[]},"trace":[]}""")
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { AnalysisView(record, timeline) } } }
        compose.onNodeWithText("不明（unknown）").assertExists()
        compose.onNodeWithText("観測が足りないため、利用意図を判断できません。").assertExists()
        compose.onAllNodesWithText("根拠を見る", substring = true).assertCountEquals(0)
    }
}
