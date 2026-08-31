/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.kollnig.missioncontrol.ui.compose

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import net.kollnig.missioncontrol.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InsightsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersLoadingStateWithoutShareAction() {
        composeRule.setContent {
            TrackerControlTheme {
                InsightsScreenContent(
                    model = InsightsScreenModel.loading(),
                    callbacks = NoOpInsightsCallbacks
                )
            }
        }

        composeRule.onNodeWithTag("insights-loading").assertExists()
        composeRule.onAllNodes(hasTestTag("insights-share"), useUnmergedTree = true)
            .assertCountEquals(0)
    }

    @Test
    fun rendersEmptyStateWithoutShareAction() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            TrackerControlTheme {
                InsightsScreenContent(
                    model = InsightsScreenModel(InsightsUiState.Empty),
                    callbacks = NoOpInsightsCallbacks
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.insights_no_data)).assertExists()
        composeRule.onAllNodes(hasTestTag("insights-share"), useUnmergedTree = true)
            .assertCountEquals(0)
    }

    @Test
    fun rendersPopulatedSectionsAndForwardsShare() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var shareClicks = 0
        composeRule.setContent {
            TrackerControlTheme {
                InsightsScreenContent(
                    model = InsightsScreenModel(
                        InsightsUiState.Populated(
                            InsightsScreenData(
                                totalTrackingAttempts = 1_247,
                                blockedTrackingAttempts = 891,
                                allowedTrackingAttempts = 356,
                                uniqueTrackerCompanies = 34,
                                appsWithTrackers = 12,
                                blockedPercentage = 71,
                                allowedPercentage = 29,
                                pervasiveTrackers = listOf(
                                    InsightsListItem("Example Analytics", 9),
                                    InsightsListItem("Social Metrics", 5)
                                ),
                                topDomains = listOf(
                                    InsightsListItem("metrics.example.com", 8),
                                    InsightsListItem("ads.example.net", 5),
                                    InsightsListItem("single.example.org", 1)
                                )
                            )
                        )
                    ),
                    callbacks = object : InsightsScreenCallbacks {
                        override fun onShare() {
                            shareClicks++
                        }
                    }
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.insights_tracking_attempts)).assertExists()
        composeRule.onNodeWithTag("insights-blocked-progress")
            .assertRangeInfoEquals(ProgressBarRangeInfo(0.71f, 0f..1f))
        composeRule.onNodeWithText(context.getString(R.string.insights_pervasive_trackers))
            .performScrollTo()
            .assertExists()
        composeRule.onNodeWithText("Example Analytics").performScrollTo().assertExists()
        composeRule.onAllNodes(hasTestTag("insights-reach-card"), useUnmergedTree = true)
            .assertCountEquals(1)
        composeRule.onNodeWithText(context.getString(R.string.insights_top_domains))
            .performScrollTo()
            .assertExists()
        composeRule.onNodeWithText("metrics.example.com").performScrollTo().assertExists()
        composeRule.onNodeWithText(
            context.resources.getQuantityString(R.plurals.insights_apps_count, 1, 1)
        ).performScrollTo().assertExists()
        composeRule.onNodeWithContentDescription(context.getString(R.string.insights_share))
            .performClick()
        composeRule.runOnIdle {
            assertEquals(1, shareClicks)
        }
    }

    private object NoOpInsightsCallbacks : InsightsScreenCallbacks {
        override fun onShare() = Unit
    }
}
