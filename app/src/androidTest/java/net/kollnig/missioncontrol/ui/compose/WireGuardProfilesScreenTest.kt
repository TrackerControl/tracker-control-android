/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * TrackerControl is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with TrackerControl. If not, see <http://www.gnu.org/licenses/>.
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
class WireGuardProfilesScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun forwardsEditAndDeleteCallbacksByIdShowsActiveStatusAndNoTerminalDivider() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = row("first", "vpn-one.example:51820", active = true)
        val second = row("second", null, active = false)
        var editId: String? = null
        var deleteId: String? = null

        composeRule.setContent {
            TrackerControlTheme {
                WireGuardProfilesScreenContent(
                    model = WireGuardProfilesScreenModel(
                        rows = listOf(first, second),
                        addContentDescription = "Add profile"
                    ),
                    callbacks = object : WireGuardProfilesScreenCallbacks {
                        override fun onAdd() = Unit
                        override fun onEdit(id: String) {
                            editId = id
                        }

                        override fun onDelete(id: String) {
                            deleteId = id
                        }
                    }
                )
            }
        }

        composeRule.onNodeWithTag("wg-profile-row-first").performClick()
        composeRule.onNodeWithTag("wg-profile-delete-second").performClick()
        composeRule.onNodeWithText(context.getString(R.string.msg_wg_profile_active)).assertExists()
        composeRule.onAllNodes(hasTestTag("wg-profile-divider"), useUnmergedTree = true)
            .assertCountEquals(1)

        composeRule.runOnIdle {
            assertEquals("first", editId)
            assertEquals("second", deleteId)
        }
    }

    @Test
    fun rendersEmptyStateAndKeepsFabReachableWithoutDividers() {
        var addClicks = 0

        composeRule.setContent {
            TrackerControlTheme {
                WireGuardProfilesScreenContent(
                    model = WireGuardProfilesScreenModel(
                        rows = emptyList(),
                        addContentDescription = "Add profile"
                    ),
                    callbacks = object : WireGuardProfilesScreenCallbacks {
                        override fun onAdd() {
                            addClicks++
                        }

                        override fun onEdit(id: String) = Unit
                        override fun onDelete(id: String) = Unit
                    }
                )
            }
        }

        composeRule.onNodeWithTag("wg-profile-empty").assertExists()
        composeRule.onNodeWithText(contextString(R.string.msg_wg_profile_empty)).assertExists()
        composeRule.onNodeWithContentDescription("Add profile").performClick()
        composeRule.onAllNodes(hasTestTag("wg-profile-divider"), useUnmergedTree = true)
            .assertCountEquals(0)

        composeRule.runOnIdle {
            assertEquals(1, addClicks)
        }
    }

    private fun row(id: String, summary: String?, active: Boolean) = WireGuardProfileRow(
        id = id,
        name = "Profile $id",
        summary = summary,
        active = active,
        activeLabel = contextString(R.string.msg_wg_profile_active)
    )

    private fun contextString(resource: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(resource)
}
