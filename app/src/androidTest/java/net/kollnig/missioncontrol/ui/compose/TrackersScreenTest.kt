/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.kollnig.missioncontrol.ui.compose

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.kollnig.missioncontrol.R
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrackersScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun usesSharedHeadingAndForwardsLibraryNavigation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var librariesClicked = false
        val callbacks = object : TrackersScreenCallbacks {
            override fun onAppStateClick() = Unit
            override fun onLibrariesClick() { librariesClicked = true }
            override fun onSectionToggle(categoryName: String, checked: Boolean) = Unit
            override fun onCompanyClick(blockingKey: String) = Unit
            override fun onCompanyToggle(blockingKey: String, checked: Boolean) = Unit
            override fun onShowMore(categoryName: String) = Unit
            override fun onRefresh() = Unit
        }

        composeRule.setContent {
            TrackerControlTheme {
                TrackersScreenContent(
                    model = TrackersScreenModel(
                        browserWarning = false,
                        appStateTitle = "Protection",
                        appStateValue = "Protected",
                        appStateHint = "",
                        appStateHintAccent = true,
                        librarySummary = "Not analysed",
                        rows = emptyList()
                    ),
                    callbacks = callbacks
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.block_tracking)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.libraries_row_heading)).performClick()
        composeRule.runOnIdle { assertTrue(librariesClicked) }
    }
}
