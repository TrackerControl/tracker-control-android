/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.kollnig.missioncontrol.ui.compose

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import net.kollnig.missioncontrol.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.text.NumberFormat

@RunWith(AndroidJUnit4::class)
class TimelineScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun loadingAndPopulatedInsightsForwardActions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val callbacks = RecordingCallbacks()
        val model = mutableStateOf(TimelineScreenModel.initial())
        composeRule.setContent {
            TrackerControlTheme {
                TimelineScreenContent(
                    model = model.value,
                    callbacks = callbacks
                )
            }
        }
        composeRule.onNodeWithTag("timeline-insights-loading").assertIsDisplayed()

        composeRule.runOnIdle { model.value = populatedModel() }
        composeRule.onNodeWithText("12").assertExists()
        composeRule.onNodeWithText(NumberFormat.getPercentInstance().format(0.75)).assertExists()
        composeRule.onNodeWithTag("timeline-insights-progress")
            .assertRangeInfoEquals(ProgressBarRangeInfo(0.75f, 0f..1f))
        composeRule.onNodeWithTag("timeline-insights-card").performClick()
        composeRule.onNodeWithContentDescription(context.getString(R.string.insights_share))
            .performClick()
        composeRule.runOnIdle {
            assertEquals(1, callbacks.openInsights)
            assertEquals(1, callbacks.shareInsights)
        }
    }

    @Test
    fun allEmptyVariantsExposeOnlyTheirRelevantAction() {
        val variants = listOf(
            TimelineEmptyState.TRACKER_CONTROL_OFF to null,
            TimelineEmptyState.RECORDING_OFF to "settings",
            TimelineEmptyState.RECORDING_UNAVAILABLE to null,
            TimelineEmptyState.WATCHING to "app"
        )
        val callbacks = RecordingCallbacks()
        val model = mutableStateOf(emptyModel(variants.first().first))
        composeRule.setContent {
            TrackerControlTheme {
                TimelineScreenContent(model.value, callbacks)
            }
        }
        var expectedSettings = 0
        var expectedApp = 0
        variants.forEach { (state, action) ->
            composeRule.runOnIdle { model.value = emptyModel(state) }
            when (action) {
                "settings" -> {
                    expectedSettings++
                    composeRule.onNodeWithTag("timeline-empty-open-settings").performClick()
                }
                "app" -> {
                    expectedApp++
                    composeRule.onNodeWithTag("timeline-empty-open-app").performClick()
                }
                null -> composeRule.onAllNodes(
                    hasTestTag("timeline-empty-open-settings") or
                        hasTestTag("timeline-empty-open-app")
                    ).assertCountEquals(0)
            }
            composeRule.runOnIdle {
                assertEquals(expectedSettings, callbacks.openSettings)
                assertEquals(expectedApp, callbacks.openApp)
            }
        }
    }

    private fun emptyModel(state: TimelineEmptyState) = TimelineScreenModel(
        TimelineInsightsState.Loading,
        emptyState = state,
        showHint = false,
        rows = emptyList()
    )

    @Test
    fun hintDismissalAndSectionsUseStableRowsAndNoTerminalDivider() {
        val callbacks = RecordingCallbacks()
        composeRule.setContent {
            TrackerControlTheme {
                TimelineScreenContent(populatedModel(showHint = true), callbacks)
            }
        }
        composeRule.onNodeWithTag("timeline-hint-dismiss").performClick()
        composeRule.onNodeWithTag("timeline-app-10001").performScrollTo().performClick()
        composeRule.onNodeWithTag("timeline-app-10002").performScrollTo().assertHasNoClickAction()
        composeRule.onNodeWithText("+1 more").performScrollTo().assertExists()
        composeRule.onAllNodes(hasTestTag("timeline-divider")).assertCountEquals(1)
        composeRule.runOnIdle {
            assertEquals(1, callbacks.dismissHint)
            assertEquals(1, callbacks.entryClicks)
        }
    }

    private fun populatedModel(showHint: Boolean = false): TimelineScreenModel {
        val section = "section-test"
        val contact = { number: Int, blocked: Boolean ->
            TimelineTrackerContact(
                key = "contact-$number",
                companyName = "Company $number",
                category = "Advertising",
                blocked = blocked,
                statusLabel = if (blocked) "Blocked" else "Allowed"
            )
        }
        val known = TimelineRow.App(
            key = "known",
            sectionKey = section,
            uid = 10001,
            appName = "Known app",
            packageName = "com.example.known",
            relativeTime = "3 min ago",
            summary = "2 trackers blocked · 1 allowed",
            blockedCount = 2,
            allowedCount = 1,
            contacts = listOf(contact(1, true), contact(2, true), contact(3, false)),
            overflowCount = 1
        )
        val unknown = TimelineRow.App(
            key = "unknown",
            sectionKey = section,
            uid = 10002,
            appName = "Unidentified app (UID 10002)",
            packageName = null,
            relativeTime = "Yesterday",
            summary = "1 tracker allowed",
            blockedCount = 0,
            allowedCount = 1,
            contacts = listOf(contact(4, false)),
            overflowCount = 0
        )
        return TimelineScreenModel(
            insights = TimelineInsightsState.Populated(
                TimelineInsightsData(12, 9, 3, 4, 75, 25)
            ),
            emptyState = null,
            showHint = showHint,
            rows = listOf(TimelineRow.Section(section, "Today"), known, unknown)
        )
    }

    private class RecordingCallbacks : TimelineScreenCallbacks {
        var entryClicks = 0
        var openInsights = 0
        var shareInsights = 0
        var openApp = 0
        var openSettings = 0
        var dismissHint = 0
        var refreshes = 0

        override fun onEntryClick(uid: Int, appName: String, packageName: String) {
            entryClicks++
        }

        override fun onOpenApp() {
            openApp++
        }

        override fun onOpenSettings() {
            openSettings++
        }

        override fun onOpenInsights() {
            openInsights++
        }

        override fun onShareInsights() {
            shareInsights++
        }

        override fun onDismissHint() {
            dismissHint++
        }

        override fun onRefresh() {
            refreshes++
        }
    }
}
