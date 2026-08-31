/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.kollnig.missioncontrol.ui.compose

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.kollnig.missioncontrol.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrackersScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Records every callback payload so tests can assert the exact arguments. */
    private class RecordingCallbacks : TrackersScreenCallbacks {
        var appStateClicked = false
        var librariesClicked = false
        var sectionToggle: Pair<String, Boolean>? = null
        var companyClicked: String? = null
        var companyToggle: Pair<String, Boolean>? = null
        var showMore: String? = null

        override fun onAppStateClick() {
            appStateClicked = true
        }

        override fun onLibrariesClick() {
            librariesClicked = true
        }

        override fun onSectionToggle(categoryName: String, checked: Boolean) {
            sectionToggle = categoryName to checked
        }

        override fun onCompanyClick(blockingKey: String) {
            companyClicked = blockingKey
        }

        override fun onCompanyToggle(blockingKey: String, checked: Boolean) {
            companyToggle = blockingKey to checked
        }

        override fun onShowMore(categoryName: String) {
            showMore = categoryName
        }
    }

    private fun categoryDescription(displayName: String) =
        context.getString(R.string.toggle_block_category_description, displayName)

    private fun allowLabel(companyName: String) =
        context.getString(R.string.feed_allow_company_in_app, companyName, "Example App")

    private fun model(rows: List<TrackersRow>) = TrackersScreenModel(
        browserWarning = false,
        appStateTitle = "Protection",
        appStateValue = "Protected",
        appStateHint = "",
        appStateHintAccent = true,
        librarySummary = "Not analysed",
        rows = rows
    )

    private fun section(
        categoryName: String,
        displayName: String = categoryName,
        checked: Boolean,
        enabled: Boolean
    ) = TrackersRow.Section(
        key = "section:$categoryName",
        categoryName = categoryName,
        title = displayName,
        lastContact = "Last contact 3 minutes ago",
        explainer = null,
        switchChecked = checked,
        switchEnabled = enabled,
        switchDescription = categoryDescription(displayName)
    )

    private fun company(
        categoryName: String,
        blockingKey: String,
        name: String,
        statusBlocked: Boolean,
        expanded: Boolean,
        hosts: String = "",
        allowChecked: Boolean = !statusBlocked,
        allowEnabled: Boolean = true,
        sharedIpNote: String? = null,
        showDivider: Boolean = false
    ) = TrackersRow.Company(
        key = "company:$categoryName:$blockingKey",
        blockingKey = blockingKey,
        name = name,
        lastSeen = "3 minutes ago",
        status = if (statusBlocked) "Blocked" else "Allowed by you",
        statusBlocked = statusBlocked,
        expanded = expanded,
        hosts = if (expanded) hosts else "",
        uncertain = false,
        showAllowSwitch = expanded,
        allowSwitchLabel = allowLabel(name),
        allowSwitchChecked = allowChecked,
        allowSwitchEnabled = allowEnabled,
        sharedIpNote = sharedIpNote,
        showDivider = showDivider,
        expandedDescription = context.getString(R.string.feed_company_expanded),
        collapsedDescription = context.getString(R.string.feed_company_collapsed)
    )

    private fun setContent(rows: List<TrackersRow>, callbacks: TrackersScreenCallbacks) {
        composeRule.setContent {
            TrackerControlTheme {
                TrackersScreenContent(model = model(rows), callbacks = callbacks)
            }
        }
    }

    /** Brings a lazily composed row into view before interacting with it. */
    private fun scrollTo(matcher: androidx.compose.ui.test.SemanticsMatcher) {
        composeRule.onNode(hasScrollAction()).performScrollToNode(matcher)
    }

    @Test
    fun usesSharedHeadingAndForwardsLibraryNavigation() {
        val callbacks = RecordingCallbacks()
        setContent(emptyList(), callbacks)

        composeRule.onNodeWithText(context.getString(R.string.block_tracking)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.libraries_row_heading)).performClick()
        composeRule.runOnIdle { assertTrue(callbacks.librariesClicked) }
    }

    @Test
    fun emptyRowListRendersHeaderWithoutAnySwitches() {
        val callbacks = RecordingCallbacks()
        setContent(emptyList(), callbacks)

        composeRule.onNodeWithText("Protection").assertExists()
        composeRule.onNodeWithText("Not analysed").assertExists()
        composeRule.onAllNodes(isToggleable()).assertCountEquals(0)
        composeRule.runOnIdle {
            assertNull(callbacks.sectionToggle)
            assertNull(callbacks.companyToggle)
        }
    }

    @Test
    fun appStateRowForwardsNavigationToProtection() {
        val callbacks = RecordingCallbacks()
        setContent(emptyList(), callbacks)

        composeRule.onNodeWithText("Protection").performClick()
        composeRule.runOnIdle { assertTrue(callbacks.appStateClicked) }
    }

    @Test
    fun sectionSwitchForwardsCategoryNameAndUncheckedTargetState() {
        val callbacks = RecordingCallbacks()
        setContent(
            listOf(
                section("Analytics", checked = true, enabled = true),
                section("Advertising", checked = false, enabled = true)
            ),
            callbacks
        )

        val analytics = hasContentDescription(categoryDescription("Analytics"))
        scrollTo(analytics)
        composeRule.onNode(analytics).assertIsOn().performClick()
        composeRule.runOnIdle { assertEquals("Analytics" to false, callbacks.sectionToggle) }
    }

    @Test
    fun sectionSwitchForwardsCategoryNameAndCheckedTargetState() {
        val callbacks = RecordingCallbacks()
        setContent(
            listOf(
                section("Analytics", checked = true, enabled = true),
                section("Advertising", checked = false, enabled = true)
            ),
            callbacks
        )

        val advertising = hasContentDescription(categoryDescription("Advertising"))
        scrollTo(advertising)
        composeRule.onNode(advertising).assertIsOff().performClick()
        composeRule.runOnIdle { assertEquals("Advertising" to true, callbacks.sectionToggle) }
    }

    @Test
    fun disabledSectionSwitchDoesNotForwardToggle() {
        val callbacks = RecordingCallbacks()
        setContent(
            listOf(section("Advertising", checked = false, enabled = false)),
            callbacks
        )

        val advertising = hasContentDescription(categoryDescription("Advertising"))
        scrollTo(advertising)
        composeRule.onNode(advertising).assertIsNotEnabled().performClick()
        composeRule.runOnIdle { assertNull(callbacks.sectionToggle) }
    }

    @Test
    fun companyAllowSwitchForwardsBlockingKeyWhenAllowingABlockedCompany() {
        val callbacks = RecordingCallbacks()
        setContent(
            listOf(
                section("Analytics", checked = true, enabled = true),
                company(
                    categoryName = "Analytics",
                    blockingKey = "example-analytics",
                    name = "Example Analytics",
                    statusBlocked = true,
                    expanded = true,
                    hosts = "tracker.example"
                )
            ),
            callbacks
        )

        val allow = hasContentDescription(allowLabel("Example Analytics"))
        scrollTo(allow)
        composeRule.onNode(allow).assertIsOff().performClick()
        composeRule.runOnIdle {
            assertEquals("example-analytics" to true, callbacks.companyToggle)
        }
    }

    @Test
    fun companyAllowSwitchForwardsBlockingKeyWhenBlockingAnAllowedCompany() {
        val callbacks = RecordingCallbacks()
        setContent(
            listOf(
                section("Analytics", checked = true, enabled = true),
                company(
                    categoryName = "Analytics",
                    blockingKey = "example-ads",
                    name = "Example Ads",
                    statusBlocked = false,
                    expanded = true,
                    hosts = "ads.example"
                )
            ),
            callbacks
        )

        val allow = hasContentDescription(allowLabel("Example Ads"))
        scrollTo(allow)
        composeRule.onNode(allow).assertIsOn().performClick()
        composeRule.runOnIdle {
            assertEquals("example-ads" to false, callbacks.companyToggle)
        }
    }

    @Test
    fun disabledCompanyAllowSwitchDoesNotForwardToggle() {
        val callbacks = RecordingCallbacks()
        setContent(
            listOf(
                // Category unblocked, so the per-company switch is read-only.
                section("Analytics", checked = false, enabled = true),
                company(
                    categoryName = "Analytics",
                    blockingKey = "frozen-co",
                    name = "Frozen Co",
                    statusBlocked = false,
                    expanded = true,
                    hosts = "frozen.example",
                    allowEnabled = false,
                    sharedIpNote = context.getString(R.string.category_unblocked_warning)
                )
            ),
            callbacks
        )

        val allow = hasContentDescription(allowLabel("Frozen Co"))
        scrollTo(allow)
        composeRule.onNode(allow).assertIsNotEnabled().performClick()
        composeRule.runOnIdle { assertNull(callbacks.companyToggle) }
    }

    @Test
    fun internetBlockedCompanySwitchStaysInertAcrossMixedCategories() {
        val callbacks = RecordingCallbacks()
        setContent(
            listOf(
                section("Analytics", checked = true, enabled = false),
                company(
                    categoryName = "Analytics",
                    blockingKey = "no-internet-co",
                    name = "No Internet Co",
                    statusBlocked = true,
                    expanded = true,
                    hosts = "offline.example",
                    allowEnabled = false
                )
            ),
            callbacks
        )

        val allow = hasContentDescription(allowLabel("No Internet Co"))
        scrollTo(allow)
        composeRule.onNode(allow).assertIsNotEnabled().performClick()
        val category = hasContentDescription(categoryDescription("Analytics"))
        scrollTo(category)
        composeRule.onNode(category).assertIsNotEnabled().performClick()
        composeRule.runOnIdle {
            assertNull(callbacks.companyToggle)
            assertNull(callbacks.sectionToggle)
        }
    }

    @Test
    fun expandedCompanyShowsHostsAndAllowSwitchWhileCollapsedCompanyHidesThem() {
        val callbacks = RecordingCallbacks()
        setContent(
            listOf(
                section("Analytics", checked = true, enabled = true),
                company(
                    categoryName = "Analytics",
                    blockingKey = "example-analytics",
                    name = "Example Analytics",
                    statusBlocked = true,
                    expanded = true,
                    hosts = "tracker.example",
                    showDivider = true
                ),
                company(
                    categoryName = "Analytics",
                    blockingKey = "example-ads",
                    name = "Example Ads",
                    statusBlocked = false,
                    expanded = false,
                    hosts = "ads.example"
                )
            ),
            callbacks
        )

        scrollTo(hasText("tracker.example"))
        composeRule.onNodeWithText("tracker.example").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(allowLabel("Example Analytics")).assertExists()
        composeRule.onNodeWithText("ads.example").assertDoesNotExist()
        composeRule.onAllNodes(hasContentDescription(allowLabel("Example Ads")))
            .assertCountEquals(0)
    }

    @Test
    fun collapsedCompanyRowForwardsExpansionRequestWithItsBlockingKey() {
        val callbacks = RecordingCallbacks()
        setContent(
            listOf(
                section("Analytics", checked = true, enabled = true),
                company(
                    categoryName = "Analytics",
                    blockingKey = "example-ads",
                    name = "Example Ads",
                    statusBlocked = false,
                    expanded = false
                )
            ),
            callbacks
        )

        scrollTo(hasText("Example Ads"))
        composeRule.onNodeWithText("Example Ads").performClick()
        composeRule.runOnIdle { assertEquals("example-ads", callbacks.companyClicked) }
    }

    @Test
    fun showMoreRowForwardsItsCategoryName() {
        val callbacks = RecordingCallbacks()
        setContent(
            listOf(
                section("Analytics", checked = true, enabled = true),
                company(
                    categoryName = "Analytics",
                    blockingKey = "example-analytics",
                    name = "Example Analytics",
                    statusBlocked = true,
                    expanded = false,
                    showDivider = true
                ),
                TrackersRow.ShowMore(
                    key = "more:Analytics",
                    text = "Show 3 more companies",
                    categoryName = "Analytics"
                )
            ),
            callbacks
        )

        scrollTo(hasText("Show 3 more companies"))
        composeRule.onNodeWithText("Show 3 more companies").performClick()
        composeRule.runOnIdle { assertEquals("Analytics", callbacks.showMore) }
    }
}
