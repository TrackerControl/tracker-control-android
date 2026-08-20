package eu.faircode.netguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.database.Cursor;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * Covers getQAName's ordering: when a shared IP carries DNS evidence for
 * several qnames (see issue #655), the most recently observed qname should
 * be attributed first rather than the alphabetically-first one, and a qname
 * with several observed rows (e.g. distinct CNAME targets) should collapse
 * to its single freshest row.
 */
@RunWith(RobolectricTestRunner.class)
public class DatabaseHelperDnsAttributionTest {

    @Test
    public void mostRecentlyObservedQnameIsReturnedFirst() {
        DatabaseHelper dh = DatabaseHelper.getInstance(RuntimeEnvironment.getApplication());
        dh.clearDns();

        String ip = "203.0.113.10";
        // Alphabetically "aaa..." would sort first, but "zzz..." was resolved later.
        dh.insertDns(rr(1_000L, "aaa-old.example.com", "aaa-old.example.com", ip, 3600));
        dh.insertDns(rr(2_000L, "zzz-new.example.com", "zzz-new.example.com", ip, 3600));

        try (Cursor c = dh.getQAName(-1, ip, false)) {
            assertEquals(2, c.getCount());
            assertTrue(c.moveToFirst());
            assertEquals("zzz-new.example.com", c.getString(c.getColumnIndexOrThrow("qname")));
            assertTrue(c.moveToNext());
            assertEquals("aaa-old.example.com", c.getString(c.getColumnIndexOrThrow("qname")));
        }
    }

    @Test
    public void repeatedObservationsOfSameQnameCollapseToFreshestRow() {
        DatabaseHelper dh = DatabaseHelper.getInstance(RuntimeEnvironment.getApplication());
        dh.clearDns();

        String ip = "203.0.113.20";
        // Same qname, two distinct CNAME targets observed at different times.
        dh.insertDns(rr(1_000L, "tracker.example.com", "old-cname.example.com", ip, 3600));
        dh.insertDns(rr(5_000L, "tracker.example.com", "new-cname.example.com", ip, 3600));

        try (Cursor c = dh.getQAName(-1, ip, false)) {
            assertEquals("duplicate rows for the same qname must collapse to one", 1, c.getCount());
            assertTrue(c.moveToFirst());
            assertEquals("tracker.example.com", c.getString(c.getColumnIndexOrThrow("qname")));
            assertEquals("the freshest row's aname should win",
                    "new-cname.example.com", c.getString(c.getColumnIndexOrThrow("aname")));
            assertEquals(5_000L, c.getLong(c.getColumnIndexOrThrow("time")));
        }
    }

    @Test
    public void aliveFilterAppliesBeforeDedup() {
        DatabaseHelper dh = DatabaseHelper.getInstance(RuntimeEnvironment.getApplication());
        dh.clearDns();

        String ip = "203.0.113.30";
        // The freshest observation has already expired; an older one is still
        // alive. With alive=true the expired row must not shadow the alive one.
        // Rows are inserted directly because insertDns() clamps the TTL to the
        // "ttl" preference floor, which would keep the fresh row alive.
        long now = System.currentTimeMillis();
        dh.getWritableDatabase().execSQL(
                "INSERT INTO dns (time, qname, aname, resource, ttl) VALUES ("
                        + (now - 10_000) + ", 't2.example.com', 'alive-cname.example.com', '"
                        + ip + "', 3600000)");
        dh.getWritableDatabase().execSQL(
                "INSERT INTO dns (time, qname, aname, resource, ttl) VALUES ("
                        + (now - 5_000) + ", 't2.example.com', 'expired-cname.example.com', '"
                        + ip + "', 1000)");

        try (Cursor c = dh.getQAName(-1, ip, true)) {
            assertEquals(1, c.getCount());
            assertTrue(c.moveToFirst());
            assertEquals("alive-cname.example.com", c.getString(c.getColumnIndexOrThrow("aname")));
        }
    }

    private static ResourceRecord rr(long time, String qname, String aname, String resource, int ttl) {
        ResourceRecord rr = new ResourceRecord();
        rr.Time = time;
        rr.QName = qname;
        rr.AName = aname;
        rr.Resource = resource;
        rr.TTL = ttl;
        return rr;
    }
}
