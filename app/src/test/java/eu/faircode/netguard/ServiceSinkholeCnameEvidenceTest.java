package eu.faircode.netguard;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.preference.PreferenceManager;

import net.kollnig.missioncontrol.data.BlockingMode;
import net.kollnig.missioncontrol.data.TrackerBlocklist;
import net.kollnig.missioncontrol.data.TrackerList;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.lang.reflect.Method;

@RunWith(RobolectricTestRunner.class)
public class ServiceSinkholeCnameEvidenceTest {
    private static final String IP = "203.0.113.211";
    private static final int UID = 54321;

    private static class TestService extends ServiceSinkhole {
        void attach(Context context) {
            attachBaseContext(context);
        }
    }

    @Test
    public void chainContinuationDoesNotCreateFalseSharedIpEvidence() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(BlockingMode.PREF_BLOCKING_MODE, BlockingMode.MODE_STANDARD)
                .commit();
        assertTrue(TrackerList.getInstance(context).loadTrackers(context));
        assertNotNull(TrackerList.findTracker("doubleclick.net"));
        assertNull(TrackerList.findTracker("front.audit-example.test"));
        TrackerBlocklist.getInstance(context).ensureDefaults(UID, false);

        DatabaseHelper database = DatabaseHelper.getInstance(context);
        database.clearDns();
        insert(database, "front.audit-example.test", "doubleclick.net");
        insert(database, "doubleclick.net", "edge.audit-example.test");
        insert(database, "edge.audit-example.test", "address.audit-example.test");

        TestService service = new TestService();
        service.attach(context);
        Method block = ServiceSinkhole.class.getDeclaredMethod(
                "blockKnownTracker", String.class, int.class);
        block.setAccessible(true);
        ServiceSinkhole.clearTrackerCaches();
        assertTrue("one connected CNAME chain keeps its tracker evidence",
                (Boolean) block.invoke(service, IP, UID));

        // The terminal name can also be resolved independently. Its direct
        // answer is benign shared-IP evidence, despite the same qname having
        // appeared as a target in the chain above.
        insert(database, "address.audit-example.test",
                "address.audit-example.test");
        ServiceSinkhole.clearTrackerCaches();
        assertFalse("an independent benign owner still marks the IP as shared",
                (Boolean) block.invoke(service, IP, UID));
    }

    private static void insert(DatabaseHelper database, String qname, String aname) {
        ResourceRecord record = new ResourceRecord();
        record.Time = System.currentTimeMillis();
        record.QName = qname;
        record.AName = aname;
        record.Resource = IP;
        record.TTL = 300;
        assertTrue(database.insertDns(record) != DatabaseHelper.DnsInsertOutcome.FAILED);
    }
}
