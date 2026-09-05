package eu.faircode.netguard;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import net.kollnig.missioncontrol.data.Tracker;

import org.junit.Test;

public class ServiceSinkholeDnsCacheInvalidationTest {
    private static final String ADDRESS = "203.0.113.200";

    @Test
    public void refreshedDnsInsertInvalidatesResourceCache() {
        ServiceSinkhole.clearTrackerCaches();
        long generation = ServiceSinkhole.trackerCache.generation();
        assertTrue(ServiceSinkhole.trackerCache.putIfGeneration(ADDRESS,
                new TrackerCache.Entry("tracker.example", new Tracker("Example", "Advertising", 0),
                        null, Long.MAX_VALUE), generation));

        ServiceSinkhole.invalidateTrackerCacheAfterDnsInsert(
                DatabaseHelper.DnsInsertOutcome.REFRESHED, ADDRESS, true);

        assertNull(ServiceSinkhole.trackerCache.get(ADDRESS, System.currentTimeMillis()));
    }

    @Test
    public void failedDnsInsertKeepsResourceCache() {
        ServiceSinkhole.clearTrackerCaches();
        long generation = ServiceSinkhole.trackerCache.generation();
        assertTrue(ServiceSinkhole.trackerCache.putIfGeneration(ADDRESS,
                new TrackerCache.Entry("tracker.example", new Tracker("Example", "Advertising", 0),
                        null, Long.MAX_VALUE), generation));

        ServiceSinkhole.invalidateTrackerCacheAfterDnsInsert(
                DatabaseHelper.DnsInsertOutcome.FAILED, ADDRESS, true);

        assertNotNull(ServiceSinkhole.trackerCache.get(ADDRESS, System.currentTimeMillis()));
    }
}
