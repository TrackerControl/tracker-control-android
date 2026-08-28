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

    private static final long NOW = System.currentTimeMillis();

    @Test
    public void firstDnsInsertReportsInserted() {
        DatabaseHelper dh = DatabaseHelper.getInstance(RuntimeEnvironment.getApplication());
        dh.clearDns();

        assertEquals(DatabaseHelper.DnsInsertOutcome.INSERTED,
                dh.insertDns(rr(NOW, "tracker.example.com", "tracker.example.com",
                        "203.0.113.1", 3600)));
    }

    @Test
    public void repeatedDnsInsertReportsRefreshed() {
        DatabaseHelper dh = DatabaseHelper.getInstance(RuntimeEnvironment.getApplication());
        dh.clearDns();

        ResourceRecord record = rr(NOW - 2_000L, "tracker.example.com", "tracker.example.com",
                "203.0.113.2", 3600);
        assertEquals(DatabaseHelper.DnsInsertOutcome.INSERTED, dh.insertDns(record));
        assertEquals(DatabaseHelper.DnsInsertOutcome.REFRESHED,
                dh.insertDns(rr(NOW, "tracker.example.com", "tracker.example.com",
                        "203.0.113.2", 120)));
    }

    @Test
    public void differentResourceReportsInserted() {
        DatabaseHelper dh = DatabaseHelper.getInstance(RuntimeEnvironment.getApplication());
        dh.clearDns();

        assertEquals(DatabaseHelper.DnsInsertOutcome.INSERTED,
                dh.insertDns(rr(NOW, "tracker.example.com", "tracker.example.com",
                        "203.0.113.3", 3600)));
        assertEquals(DatabaseHelper.DnsInsertOutcome.INSERTED,
                dh.insertDns(rr(NOW, "tracker.example.com", "tracker.example.com",
                        "203.0.113.4", 120)));
    }

    @Test
    public void mostRecentlyObservedQnameIsReturnedFirst() {
        DatabaseHelper dh = DatabaseHelper.getInstance(RuntimeEnvironment.getApplication());
        dh.clearDns();

        String ip = "203.0.113.10";
        // Alphabetically "aaa..." would sort first, but "zzz..." was resolved later.
        dh.insertDns(rr(NOW - 2_000L, "aaa-old.example.com", "aaa-old.example.com", ip, 3600));
        dh.insertDns(rr(NOW - 1_000L, "zzz-new.example.com", "zzz-new.example.com", ip, 3600));

        try (Cursor c = dh.getQAName(-1, ip)) {
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
        dh.insertDns(rr(NOW - 5_000L, "tracker.example.com", "old-cname.example.com", ip, 3600));
        dh.insertDns(rr(NOW - 1_000L, "tracker.example.com", "new-cname.example.com", ip, 3600));

        try (Cursor c = dh.getQAName(-1, ip)) {
            assertEquals("duplicate rows for the same qname must collapse to one", 1, c.getCount());
            assertTrue(c.moveToFirst());
            assertEquals("tracker.example.com", c.getString(c.getColumnIndexOrThrow("qname")));
            assertEquals("the freshest row's aname should win",
                    "new-cname.example.com", c.getString(c.getColumnIndexOrThrow("aname")));
            assertEquals(NOW - 1_000L, c.getLong(c.getColumnIndexOrThrow("time")));
        }
    }

    @Test
    public void caseVariantQnamesShareOneRowAndDoNotLookUncertain() {
        DatabaseHelper dh = DatabaseHelper.getInstance(RuntimeEnvironment.getApplication());
        dh.clearDns();

        String ip = "203.0.113.25";
        assertEquals(DatabaseHelper.DnsInsertOutcome.INSERTED,
                dh.insertDns(rr(NOW - 5_000L, "graph.facebook.com", "alias.example.com", ip, 3600)));
        assertEquals(DatabaseHelper.DnsInsertOutcome.REFRESHED,
                dh.insertDns(rr(NOW - 1_000L, "Graph.Facebook.Com", "Alias.Example.Com", ip, 3600)));

        try (Cursor c = dh.getQAName(-1, ip)) {
            assertEquals("case variants must share one stored qname", 1, c.getCount());
            assertTrue(c.moveToFirst());
            assertEquals("graph.facebook.com", c.getString(c.getColumnIndexOrThrow("qname")));
            assertEquals("alias.example.com", c.getString(c.getColumnIndexOrThrow("aname")));
            assertEquals(NOW - 1_000L, c.getLong(c.getColumnIndexOrThrow("time")));
        }
    }

    @Test
    public void aliveFilterAppliesBeforeDedup() {
        DatabaseHelper dh = DatabaseHelper.getInstance(RuntimeEnvironment.getApplication());
        dh.clearDns();

        String ip = "203.0.113.30";
        // The freshest observation has already expired; an older one is still
        // alive. The expired row must not shadow the alive one.
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

        try (Cursor c = dh.getQAName(-1, ip)) {
            assertEquals(1, c.getCount());
            assertTrue(c.moveToFirst());
            assertEquals("alive-cname.example.com", c.getString(c.getColumnIndexOrThrow("aname")));
        }
    }

    /**
     * Issue #759: an expired qname must not make an IP look shared. The UI
     * derived its shared-IP marker from a row set that still contained
     * expired evidence while the blocker had already dropped it, so the log
     * flagged an ambiguity the blocking decision never saw.
     */
    @Test
    public void expiredQnameDoesNotMakeIpLookShared() {
        DatabaseHelper dh = DatabaseHelper.getInstance(RuntimeEnvironment.getApplication());
        dh.clearDns();

        String ip = "203.0.113.40";
        // Two different qnames on one IP, but only one is still alive. Rows are
        // inserted directly because insertDns() clamps the TTL to the "ttl"
        // preference floor, which would keep the expired one alive.
        long now = System.currentTimeMillis();
        dh.getWritableDatabase().execSQL(
                "INSERT INTO dns (time, qname, aname, resource, ttl) VALUES ("
                        + (now - 10_000) + ", 'alive.example.com', 'alive.example.com', '"
                        + ip + "', 3600000)");
        dh.getWritableDatabase().execSQL(
                "INSERT INTO dns (time, qname, aname, resource, ttl) VALUES ("
                        + (now - 5_000) + ", 'expired.example.com', 'expired.example.com', '"
                        + ip + "', 1000)");

        try (Cursor c = dh.getQAName(-1, ip)) {
            assertEquals("an expired qname must not count towards shared-IP", 1, c.getCount());
            assertTrue(c.moveToFirst());
            assertEquals("alive.example.com", c.getString(c.getColumnIndexOrThrow("qname")));
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
