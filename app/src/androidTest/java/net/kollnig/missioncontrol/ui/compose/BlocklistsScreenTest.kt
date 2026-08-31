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
import androidx.compose.ui.test.onNodeWithTag
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
class BlocklistsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun forwardsAddEditEnableAndDeleteCallbacksWithoutTerminalDivider() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = row("first", "https://one.example/hosts.txt", enabled = true)
        val second = row("second", "https://two.example/hosts.txt", enabled = false)
        var addClicks = 0
        var editUuid: String? = null
        var enabledUuid: String? = null
        var enabledValue: Boolean? = null
        var deleteUuid: String? = null

        composeRule.setContent {
            TrackerControlTheme {
                BlocklistsScreenContent(
                    model = BlocklistsScreenModel(listOf(first, second)),
                    callbacks = object : BlocklistsScreenCallbacks {
                        override fun onAdd() {
                            addClicks++
                        }

                        override fun onEdit(uuid: String) {
                            editUuid = uuid
                        }

                        override fun onEnabledChanged(uuid: String, enabled: Boolean) {
                            enabledUuid = uuid
                            enabledValue = enabled
                        }

                        override fun onDelete(uuid: String) {
                            deleteUuid = uuid
                        }
                    }
                )
            }
        }

        composeRule.onNodeWithContentDescription(context.getString(R.string.title_add_blocklist))
            .performClick()
        composeRule.onNodeWithTag("blocklist-row-first").performClick()
        composeRule.onNodeWithTag("blocklist-switch-first").performClick()
        composeRule.onNodeWithTag("blocklist-delete-second").performClick()
        composeRule.onAllNodes(hasTestTag("blocklist-divider"), useUnmergedTree = true)
            .assertCountEquals(1)

        composeRule.runOnIdle {
            assertEquals(1, addClicks)
            assertEquals("first", editUuid)
            assertEquals("first", enabledUuid)
            assertEquals(false, enabledValue)
            assertEquals("second", deleteUuid)
        }
    }

    @Test
    fun rendersEmptyStateWithoutDividerAndKeepsAddActionReachable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var addClicks = 0

        composeRule.setContent {
            TrackerControlTheme {
                BlocklistsScreenContent(
                    model = BlocklistsScreenModel(emptyList()),
                    callbacks = object : BlocklistsScreenCallbacks {
                        override fun onAdd() {
                            addClicks++
                        }

                        override fun onEdit(uuid: String) = Unit
                        override fun onEnabledChanged(uuid: String, enabled: Boolean) = Unit
                        override fun onDelete(uuid: String) = Unit
                    }
                )
            }
        }

        composeRule.onNodeWithTag("blocklist-empty").assertExists()
        composeRule.onNodeWithText(context.getString(R.string.summary_manage_blocklists)).assertExists()
        composeRule.onNodeWithContentDescription(context.getString(R.string.title_add_blocklist))
            .performClick()
        composeRule.onAllNodes(hasTestTag("blocklist-divider"), useUnmergedTree = true)
            .assertCountEquals(0)
        composeRule.runOnIdle {
            assertEquals(1, addClicks)
        }
    }

    private fun row(uuid: String, url: String, enabled: Boolean) = BlocklistRow(
        uuid = uuid,
        url = url,
        lastUpdate = "Last updated",
        error = null,
        enabled = enabled,
        enableContentDescription = "Enable $url",
        deleteContentDescription = "Delete blocklist"
    )
}
