/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.kollnig.missioncontrol.ui.compose

import android.graphics.Color
import android.graphics.Picture
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.kollnig.missioncontrol.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CountriesScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** A trivial recorded picture; the map only needs something drawable. */
    private fun picture(): Picture = Picture().apply {
        val canvas = beginRecording(64, 32)
        canvas.drawColor(Color.LTGRAY)
        endRecording()
    }

    @Test
    fun rendersSharedHeadingAndFailureState() {
        composeRule.setContent {
            TrackerControlTheme {
                CountriesScreenContent(CountriesMapState.Failed)
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.countries)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.countries_explanation)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.countries_loading_failed)).assertExists()
    }

    @Test
    fun loadedMapAnnouncesTheHighlightedCountries() {
        val countryCodes = "DE, IE, US"
        composeRule.setContent {
            TrackerControlTheme {
                CountriesScreenContent(CountriesMapState.Loaded(picture(), countryCodes))
            }
        }

        composeRule.onNodeWithContentDescription(
            context.getString(R.string.countries_map_highlighted, countryCodes)
        ).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.countries_explanation)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.countries_loading_failed))
            .assertDoesNotExist()
    }

    @Test
    fun loadedMapWithoutDestinationsAnnouncesTheEmptyState() {
        composeRule.setContent {
            TrackerControlTheme {
                CountriesScreenContent(CountriesMapState.Loaded(picture(), ""))
            }
        }

        composeRule.onNodeWithContentDescription(
            context.getString(R.string.countries_map_none)
        ).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.countries)).assertExists()
    }
}
