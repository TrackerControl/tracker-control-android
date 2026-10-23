package net.kollnig.missioncontrol.ui.compose

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import net.kollnig.missioncontrol.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BlocklistsScreenNoticeTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private var updates = 0
    private val callbacks = object : BlocklistsScreenCallbacks {
        override fun onAdd() = Unit
        override fun onEdit(uuid: String) = Unit
        override fun onEnabledChanged(uuid: String, enabled: Boolean) = Unit
        override fun onDelete(uuid: String) = Unit
        override fun onDownload() { updates++ }
    }
    private fun row(error: String? = null) = BlocklistRow(
        "one", "https://example.com/hosts.txt", "Not downloaded yet", error,
        true, "Enable blocklist", "Delete blocklist"
    )

    @Test fun minimalNoticeAndDownloadErrorsRemainVisibleWithRetry() {
        compose.setContent {
            TrackerControlTheme {
                BlocklistsScreenContent(BlocklistsScreenModel(listOf(row("HTTP 404")),
                    context.getString(R.string.blocklist_minimal_notice)), callbacks)
            }
        }
        compose.onNodeWithTag("blocklist-mode-notice").assertIsDisplayed()
        compose.onNodeWithText("HTTP 404").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.blocklist_update)).performClick()
        assertEquals(1, updates)
        capture("blocklists-minimal")
    }

    @Test fun playNoticeDoesNotOfferUnavailableModesEvenWithNoLists() {
        compose.setContent {
            TrackerControlTheme {
                BlocklistsScreenContent(BlocklistsScreenModel(emptyList(),
                    context.getString(R.string.blocklist_play_notice)), callbacks)
            }
        }
        compose.onNodeWithText(context.getString(R.string.blocklist_play_notice)).assertIsDisplayed()
        compose.onNodeWithTag("blocklist-add").assertIsDisplayed()
        capture("blocklists-play")
    }

    @Test fun runningDownloadDisablesRetryAndListMutation() {
        compose.setContent {
            TrackerControlTheme {
                BlocklistsScreenContent(BlocklistsScreenModel(listOf(row()), null, true, true), callbacks)
            }
        }
        compose.onNodeWithTag("blocklist-downloading").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.blocklist_update)).assertIsNotEnabled()
        compose.onNodeWithTag("blocklist-add").assertIsNotEnabled()
        compose.onNodeWithTag("blocklist-switch-one").assertIsNotEnabled()
        compose.onNodeWithTag("blocklist-delete-one").assertIsNotEnabled()
        capture("blocklists-downloading")
    }

    private fun capture(name: String) {
        val output = File("build/reports/$name.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
