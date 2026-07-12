package eu.faircode.netguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Verifies the (uid, destination IP) keying of the in-memory tracker-verdict
 * caches (#655, Option 1): the same IP resolves to different cache slots for
 * different apps, so one app's cached verdict is never reused for another, and
 * a fresh DNS answer invalidates every app's slot for that IP.
 */
@RunWith(RobolectricTestRunner.class)
public class TrackerCacheKeyTest {

    @After
    public void tearDown() {
        ServiceSinkhole.clearTrackerCaches();
    }

    @Test
    public void keyIsStablePerUidAndIp() {
        assertEquals(ServiceSinkhole.trackerCacheKey(10001, "1.2.3.4"),
                ServiceSinkhole.trackerCacheKey(10001, "1.2.3.4"));
    }

    @Test
    public void keyDiffersAcrossUidsForSameIp() {
        // The whole point of Option 1: same shared IP, different apps must not
        // collide in the cache.
        assertNotEquals(ServiceSinkhole.trackerCacheKey(10001, "1.2.3.4"),
                ServiceSinkhole.trackerCacheKey(10002, "1.2.3.4"));
    }

    @Test
    public void keyDiffersAcrossIpsForSameUid() {
        assertNotEquals(ServiceSinkhole.trackerCacheKey(10001, "1.2.3.4"),
                ServiceSinkhole.trackerCacheKey(10001, "1.2.3.5"));
    }

    @Test
    public void keyExposesIpAsInvalidationPrefix() {
        // invalidateTrackerCachesForIp() relies on every per-app key starting
        // with "<ip>#"; '#' cannot appear in an IP literal so the prefix is
        // unambiguous.
        assertTrue(ServiceSinkhole.trackerCacheKey(12345, "10.0.0.7").startsWith("10.0.0.7#"));
    }

    @Test
    public void invalidationDropsAllAppsForThatIpOnly() {
        long soon = System.currentTimeMillis() + 60_000L;
        String ip = "1.2.3.4";
        String otherIp = "5.6.7.8";

        ServiceSinkhole.ipToTracker.put(ServiceSinkhole.trackerCacheKey(10001, ip),
                new ServiceSinkhole.Expiring<>(ServiceSinkhole.NO_TRACKER, soon));
        ServiceSinkhole.ipToTracker.put(ServiceSinkhole.trackerCacheKey(10002, ip),
                new ServiceSinkhole.Expiring<>(ServiceSinkhole.NO_TRACKER, soon));
        ServiceSinkhole.ipToHost.put(ServiceSinkhole.trackerCacheKey(10001, ip),
                new ServiceSinkhole.Expiring<>("host-a", soon));
        // A different IP must survive invalidation of `ip`.
        ServiceSinkhole.ipToTracker.put(ServiceSinkhole.trackerCacheKey(10001, otherIp),
                new ServiceSinkhole.Expiring<>(ServiceSinkhole.NO_TRACKER, soon));

        ServiceSinkhole.invalidateTrackerCachesForIp(ip);

        assertFalse(ServiceSinkhole.ipToTracker.containsKey(ServiceSinkhole.trackerCacheKey(10001, ip)));
        assertFalse(ServiceSinkhole.ipToTracker.containsKey(ServiceSinkhole.trackerCacheKey(10002, ip)));
        assertFalse(ServiceSinkhole.ipToHost.containsKey(ServiceSinkhole.trackerCacheKey(10001, ip)));
        assertTrue(ServiceSinkhole.ipToTracker.containsKey(ServiceSinkhole.trackerCacheKey(10001, otherIp)));
    }
}
