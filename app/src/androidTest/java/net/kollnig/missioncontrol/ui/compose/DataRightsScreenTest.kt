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
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DataRightsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersOptionalSectionAndForwardsActions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var selectedAction = 0

        composeRule.setContent {
            TrackerControlTheme {
                DataRightsScreen(showAdSettings = true) { selectedAction = it }
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.personalised_ads)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.request_data)).performClick()
        composeRule.runOnIdle {
            assertEquals(R.id.btnReqData, selectedAction)
        }
    }
}
