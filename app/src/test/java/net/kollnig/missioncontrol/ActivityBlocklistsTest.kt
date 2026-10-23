package net.kollnig.missioncontrol

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import net.kollnig.missioncontrol.data.BlockingMode
import net.kollnig.missioncontrol.data.BlocklistManager
import net.kollnig.missioncontrol.ui.compose.BlocklistsScreenModel
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ActivityBlocklistsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before fun setupWork() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }

    @Test fun addingListQueuesConstrainedDownloadWithoutReplacingCachedHosts() {
        val cached = java.io.File(context.filesDir, "hosts.txt")
        cached.writeText("0.0.0.0 old.example\n")
        val controller = Robolectric.buildActivity(ActivityBlocklists::class.java).setup()
        val activity = controller.get()
        addUrl(activity, "https://example.com/custom-hosts")
        val work = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("CustomBlocklistsDownload").get()
        assertEquals(1, work.size)
        assertEquals(NetworkType.CONNECTED, work.single().constraints.requiredNetworkType)
        assertFalse(work.single().state.isFinished)
        assertTrue(BlocklistManager.getInstance(context).blocklists.any {
            it.url == "https://example.com/custom-hosts"
        })
        assertEquals("0.0.0.0 old.example\n", cached.readText())
        controller.recreate()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(model(controller.get()).downloading)
        controller.pause().stop().destroy()
    }

    @Test fun noticesRespectBuildAndResearchOverride() {
        val controller = Robolectric.buildActivity(ActivityBlocklists::class.java).setup()
        val activity = controller.get()
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putString("blocking_mode", "minimal").putBoolean("log_logcat", false).commit()
        assertEquals(activity.getString(if (BlockingMode.isPlayStoreBuild())
            R.string.blocklist_play_notice else R.string.blocklist_minimal_notice), model(activity).notice)
        prefs.edit().putString("blocking_mode", "strict").putBoolean("log_logcat", true).commit()
        assertEquals(activity.getString(if (BlockingMode.isPlayStoreBuild())
            R.string.blocklist_play_notice else R.string.blocklist_research_notice), model(activity).notice)
        prefs.edit().putBoolean("log_logcat", false).commit()
        if (BlockingMode.isPlayStoreBuild())
            assertEquals(activity.getString(R.string.blocklist_play_notice), model(activity).notice)
        else
            assertNull(model(activity).notice)
        controller.pause().stop().destroy()
    }

    private fun model(activity: ActivityBlocklists): BlocklistsScreenModel =
        ActivityBlocklists::class.java.getDeclaredMethod("buildScreenModel").apply {
            isAccessible = true
        }.invoke(activity) as BlocklistsScreenModel

    private fun addUrl(activity: ActivityBlocklists, url: String) {
        ActivityBlocklists::class.java.getDeclaredMethod("showAddDialog", String::class.java).apply {
            isAccessible = true
        }.invoke(activity, null)
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        findInput(dialog.window!!.decorView)!!.setText(url)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun findInput(view: View): EditText? {
        if (view is EditText) return view
        if (view is ViewGroup) for (i in 0 until view.childCount)
            findInput(view.getChildAt(i))?.let { return it }
        return null
    }
}
