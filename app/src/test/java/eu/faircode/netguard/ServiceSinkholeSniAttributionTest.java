package eu.faircode.netguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.util.Pair;

import androidx.preference.PreferenceManager;

import net.kollnig.missioncontrol.data.BlockingMode;
import net.kollnig.missioncontrol.data.Tracker;
import net.kollnig.missioncontrol.data.TrackerList;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/** How research mode attributes a contact by its SNI. */
@RunWith(RobolectricTestRunner.class)
public class ServiceSinkholeSniAttributionTest {

    @Test
    public void cloakedTrackerIsStoredUnderTheMatchedName() {
        Context context = RuntimeEnvironment.getApplication();
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(BlockingMode.PREF_BLOCKING_MODE, BlockingMode.MODE_STANDARD)
                .commit();
        assertTrue(TrackerList.getInstance(context).loadTrackers(context));
        assertNull(TrackerList.findTracker("metrics.sni-example.test"));

        Pair<Tracker, String> cloaked =
                ServiceSinkhole.decloakTracker("metrics.sni-example.test", "doubleclick.net");
        assertNotNull(cloaked.first);
        // The stored name must be found again by the timeline's findTracker.
        assertEquals("doubleclick.net", cloaked.second);
        assertNotNull(TrackerList.findTracker(cloaked.second));

        Pair<Tracker, String> direct = ServiceSinkhole.decloakTracker("doubleclick.net", null);
        assertEquals("doubleclick.net", direct.second);

        Pair<Tracker, String> none =
                ServiceSinkhole.decloakTracker("metrics.sni-example.test", null);
        assertNull(none.first);
        assertNull(none.second);
    }

    @Test
    public void sniKeepsOnlyTheSharedIpVerdictMark() {
        assertEquals(DatabaseHelper.ACCESS_UNCERTAIN_MIXED_TRACKER_AND_NON_TRACKER,
                ServiceSinkhole.sniUncertainty(
                        DatabaseHelper.ACCESS_UNCERTAIN_MIXED_TRACKER_AND_NON_TRACKER));
        assertEquals(DatabaseHelper.ACCESS_UNCERTAIN_NONE,
                ServiceSinkhole.sniUncertainty(DatabaseHelper.ACCESS_UNCERTAIN_SHARED_IP));
        assertEquals(DatabaseHelper.ACCESS_UNCERTAIN_NONE,
                ServiceSinkhole.sniUncertainty(DatabaseHelper.ACCESS_UNCERTAIN_MULTIPLE_TRACKERS));
        assertEquals(DatabaseHelper.ACCESS_UNCERTAIN_NONE,
                ServiceSinkhole.sniUncertainty(DatabaseHelper.ACCESS_UNCERTAIN_NONE));
    }
}
