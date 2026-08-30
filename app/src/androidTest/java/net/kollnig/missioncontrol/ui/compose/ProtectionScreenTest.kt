/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.kollnig.missioncontrol.ui.compose

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.kollnig.missioncontrol.R
import net.kollnig.missioncontrol.data.AppProtectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProtectionScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersBodyAndForwardsEveryActionWithoutTerminalCompanyDivider() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var pauseClicked = false
        var selectedState: AppProtectionState? = null
        var selectedRoute: Boolean? = null
        var categoryToggle: Pair<String, Boolean>? = null
        var companyClicked: String? = null
        val categoryDescription = context.getString(
            R.string.toggle_block_category_description, "Analytics"
        )

        composeRule.setContent {
            TrackerControlTheme {
                ProtectionScreenContent(
                    model = ProtectionScreenModel(
                        pause = PauseSection("Protected", "Shared companion", "Pause now", "Lockdown note"),
                        blocked = BlockedState(
                            available = true,
                            message = null,
                            categories = listOf(
                                CategoryModel(
                                    key = "analytics",
                                    name = "analytics",
                                    title = "Analytics",
                                    lastContact = "last contact 3 minutes ago",
                                    explainer = null,
                                    switchChecked = true,
                                    switchEnabled = true,
                                    switchDescription = categoryDescription,
                                    companies = listOf(
                                        CompanyModel(
                                            blockingKey = "example-analytics",
                                            name = "Example Analytics",
                                            lastSeen = "3 minutes ago",
                                            status = "Blocked",
                                            statusBlocked = true,
                                            actionable = true,
                                            actionDescription = "Allow Example Analytics in Example App"
                                        ),
                                        CompanyModel(
                                            blockingKey = "example-ads",
                                            name = "Example Ads",
                                            lastSeen = "5 minutes ago",
                                            status = "Allowed by you",
                                            statusBlocked = false,
                                            actionable = true,
                                            actionDescription = "Allow Example Ads in Example App"
                                        )
                                    )
                                )
                            )
                        ),
                        stateOptions = listOf(
                            StateOption(AppProtectionState.PROTECTED, "Protected", "Blocked and recorded", true, true),
                            StateOption(AppProtectionState.TRACKERS_ALLOWED, "Trackers allowed", "Allowed", false, true)
                        ),
                        route = RouteModel.Available(
                            listOf(
                                RouteOption(true, "Through the remote VPN", "Tunnelled", true),
                                RouteOption(false, "Directly from this device", "Direct", false)
                            )
                        )
                    ),
                    callbacks = object : ProtectionScreenCallbacks {
                        override fun onPauseResume() {
                            pauseClicked = true
                        }

                        override fun onStateSelected(value: AppProtectionState) {
                            selectedState = value
                        }

                        override fun onRouteSelected(tunnelled: Boolean) {
                            selectedRoute = tunnelled
                        }

                        override fun onCategoryToggle(categoryKey: String, checked: Boolean) {
                            categoryToggle = categoryKey to checked
                        }

                        override fun onCompanyClick(blockingKey: String) {
                            companyClicked = blockingKey
                        }
                    }
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.protection_blocked_last_24h)).assertExists()
        composeRule.onNodeWithText("Pause now").performClick()
        composeRule.onNodeWithContentDescription(categoryDescription).performClick()
        composeRule.onNodeWithContentDescription("Allow Example Analytics in Example App").performClick()
        composeRule.onAllNodes(hasTestTag("protection-company-divider"), useUnmergedTree = true)
            .assertCountEquals(1)
        composeRule.onNodeWithText(context.getString(R.string.app_state_heading))
            .performScrollTo()
            .assertExists()
        composeRule.onNodeWithText("Trackers allowed").performScrollTo().performClick()
        composeRule.onNodeWithText(context.getString(R.string.protection_route_heading))
            .performScrollTo()
            .assertExists()
        composeRule.onNodeWithText("Directly from this device").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertTrue(pauseClicked)
            assertEquals(AppProtectionState.TRACKERS_ALLOWED, selectedState)
            assertEquals(false, selectedRoute)
            assertEquals("analytics" to false, categoryToggle)
            assertEquals("example-analytics", companyClicked)
        }
    }
}
