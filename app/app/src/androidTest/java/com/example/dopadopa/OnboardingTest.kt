package com.example.dopadopa

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class OnboardingTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun confirmedGoalAndSeparateConsent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("guard", 0)
        prefs.edit().clear().commit()
        ActivityScenario.launch(MainActivity::class.java).use {
            assertFalse(prefs.getBoolean("collection_consent", false))
            compose.onNodeWithText("Instagram / X / TikTok — 今後対応予定").assertExists().assertHasNoClickAction()
            compose.onAllNodes(isToggleable())[0].performClick()
            compose.onNodeWithText("このゴールを確認して次へ").performScrollTo().performClick()
            compose.waitUntil(10_000) { prefs.getBoolean("goal_confirmed", false) }
            compose.onNodeWithText("確認済みのゴール").assertExists()
            assertFalse(prefs.getBoolean("collection_consent", false))
            assertFalse(prefs.getBoolean("cloud_consent", false))
            // The first toggle on the confirmed screen controls local collection only.
            compose.onAllNodes(isToggleable())[0].performScrollTo().performClick()
            compose.waitUntil { prefs.getBoolean("collection_consent", false) }
            assertFalse(prefs.getBoolean("cloud_consent", false))
            compose.onNodeWithText("収集とクラウド同意を撤回").performScrollTo().performClick()
            compose.waitUntil { !prefs.getBoolean("collection_consent", false) }
            assertFalse(prefs.getBoolean("cloud_consent", false))
        }
    }
}
