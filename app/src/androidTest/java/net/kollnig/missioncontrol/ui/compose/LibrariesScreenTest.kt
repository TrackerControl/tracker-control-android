/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.kollnig.missioncontrol.ui.compose

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.kollnig.missioncontrol.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibrariesScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersResultAndForwardsActionsWithoutTerminalDivider() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var analyseClicks = 0
        var clickedWebsite: String? = null

        composeRule.setContent {
            TrackerControlTheme {
                LibrariesScreenContent(
                    model = LibrariesScreenModel(
                        explanation = "Explanation",
                        result = LibrariesResult(
                            libraries = listOf(
                                LibraryRow("Example Analytics", "https://example.com"),
                                LibraryRow("Embedded Library", null)
                            ),
                            rawText = null,
                            disclaimer = "Disclaimer"
                        ),
                        progress = null,
                        actionText = "Analyse tracker libraries",
                        actionEnabled = true
                    ),
                    callbacks = object : LibrariesScreenCallbacks {
                        override fun onAnalyse() {
                            analyseClicks++
                        }

                        override fun onWebsiteClick(website: String) {
                            clickedWebsite = website
                        }
                    }
                )
            }
        }

        composeRule.onNodeWithText(
            context.getString(R.string.detected_tracker_libraries_heading)
        ).assertExists()
        composeRule.onNodeWithText("Analyse tracker libraries").performClick()
        composeRule.onNodeWithContentDescription(
            context.getString(R.string.open_tracker_website, "Example Analytics")
        ).performClick()
        composeRule.onAllNodes(hasTestTag("libraries-divider"), useUnmergedTree = true)
            .assertCountEquals(1)
        composeRule.runOnIdle {
            assertEquals(1, analyseClicks)
            assertEquals("https://example.com", clickedWebsite)
        }
    }
}
