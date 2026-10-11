package com.example.dopadopa

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.dopadopa.ui.FeedbackView
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FeedbackViewTest {
    @get:Rule val compose = createComposeRule()
    @Test fun unansweredRequiresChoiceAndExplicitSave() {
        var saved: Pair<String, String?>? = null
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            FeedbackView(JSONArray(), true) { verdict, correction -> saved = verdict to correction }
        } } }
        compose.onNodeWithText("未回答").assertExists()
        compose.onNodeWithText("この回答を保存").assertIsNotEnabled()
        compose.onNodeWithText("違う").performScrollTo().performClick()
        compose.onNodeWithText("利用意図の訂正（任意・500文字まで）").performScrollTo().performTextInput("学習目的だった")
        assertNull(saved)
        compose.onNodeWithText("この回答を保存").performScrollTo().performClick()
        assertEquals("incorrect" to "学習目的だった", saved)
    }
    @Test fun historyIsSeparateAndRevocationDisablesWrite() {
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            FeedbackView(JSONArray("""[{"created_at":"2026-10-11T00:00:00Z","verdict":"incorrect","corrected_intent":"学習目的だった"}]"""), false) { _, _ -> fail("write without consent") }
        } } }
        compose.onNodeWithText("本人の訂正: 学習目的だった").assertExists()
        compose.onNodeWithText("違う").assertIsNotEnabled()
        compose.onNodeWithText("この回答を保存").assertIsNotEnabled()
    }
}
