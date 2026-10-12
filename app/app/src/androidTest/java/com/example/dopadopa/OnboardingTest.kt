package com.example.dopadopa

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.dopadopa.ui.GoalSelection
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercises selection without clearing device preferences, saving goals, or calling APIs. */
class OnboardingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun multipleGoalsRequireExplicitSave() {
        var saved: Set<String>? = null
        compose.setContent { MaterialTheme { Column { GoalSelection(saving = false) { saved = it } } } }
        compose.onNodeWithText("保存する").assertIsNotEnabled()
        compose.onAllNodes(isToggleable()).assertCountEquals(5)
        compose.onNodeWithText("学習・スキルアップ").performClick()
        compose.onNodeWithText("休憩・気分転換").performClick()
        compose.onNodeWithText("学習・スキルアップ").assertIsOn()
        compose.onNodeWithText("休憩・気分転換").assertIsOn()
        assertNull(saved)
        compose.onNodeWithText("保存する").performClick()
        compose.runOnIdle { assertEquals(setOf("学習・スキルアップ", "休憩・気分転換"), saved) }
    }
}
