package com.deskforge.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class DeskForgeAppTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun workspaceDashboardExposesSupportedFedoraPreset() {
        composeRule.onNodeWithText("Fedora XFCE 44").assertIsDisplayed()
        composeRule.onNodeWithText("Install workspace").assertIsDisplayed()
    }

    @Test
    fun diagnosticsCanRunWithoutStartingGuestCode() {
        composeRule.onNodeWithText("Diagnostics").performClick()
        composeRule.onNodeWithText("Run capability check").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("PRoot").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("PRoot").assertIsDisplayed()
    }
}
