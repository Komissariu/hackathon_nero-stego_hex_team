package com.example.fluffydisco

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelSettingsInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun modelSelectionShowsInstallStateWithoutChangingSettingsNavigation() {
        composeRule.onNodeWithText(text(R.string.nav_settings)).performClick()
        composeRule.onNodeWithText(text(R.string.settings_model_selection_button))
            .performScrollTo()
            .performClick()

        composeRule.onNodeWithText(text(R.string.settings_model_title)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.settings_model_not_bundled))
            .assertIsDisplayed()
    }

    @Test
    fun archiveScreenExplainsWhenApkContainsNoModels() {
        composeRule.onNodeWithText(text(R.string.nav_settings)).performClick()
        composeRule.onNodeWithText(text(R.string.settings_model_downloads_button))
            .performScrollTo()
            .performClick()

        composeRule.onNodeWithText(text(R.string.settings_model_no_bundled_archives))
            .assertIsDisplayed()
    }

    private fun text(resourceId: Int): String =
        composeRule.activity.getString(resourceId)
}
