package eu.faircode.netguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * Covers the packet-loop measurement plumbing for issue #653: the wire format
 * between the native counters and the report, the per-hour rates the comparison
 * relies on, and the system/user split.
 */
public class PacketLoopStatsTest {

    private static final class FakeUidInfo implements PacketLoopStats.UidInfo {
        final Map<Integer, String> labels = new HashMap<>();
        final Map<Integer, Boolean> system = new HashMap<>();

        @Override
        public String label(int uid) {
            return labels.get(uid);
        }

        @Override
        public boolean isSystem(int uid) {
            Boolean value = system.get(uid);
            return value != null && value;
        }
    }

    /** Builds the flat array the native side produces. */
    private static long[] raw(long elapsedMs, long wakeups, long cpuUs, long[]... uids) {
        long[] out = new long[PacketLoopStats.SCALARS + uids.length * 3];
        out[0] = elapsedMs;
        out[1] = wakeups * 2; // iterations
        out[2] = wakeups * 2; // polls
        out[3] = wakeups;
        out[4] = 7;   // timeouts
        out[5] = 3;   // recheck polls
        out[6] = 11;  // events tun
        out[7] = 13;  // events sock
        out[8] = 0;   // events pipe
        out[9] = 17;  // tun packets
        out[10] = 4096; // tun bytes
        out[11] = cpuUs;
        out[12] = 500;  // scan us
        out[13] = 900;  // dispatch us
        out[14] = 0;    // uid overflow
        out[15] = uids.length;
        for (int i = 0; i < uids.length; i++)
            System.arraycopy(uids[i], 0, out, PacketLoopStats.SCALARS + i * 3, 3);
        return out;
    }

    @Test
    public void parsesScalarsAndUidTriples() {
        PacketLoopStats stats = PacketLoopStats.parse(
                raw(60_000, 100, 250_000, new long[]{10123, 5, 40}, new long[]{10456, 2, 8}));

        assertNotNull(stats);
        assertEquals(60_000, stats.elapsedMs);
        assertEquals(100, stats.wakeups);
        assertEquals(200, stats.iterations);
        assertEquals(7, stats.timeouts);
        assertEquals(3, stats.recheckPolls);
        assertEquals(11, stats.eventsTun);
        assertEquals(13, stats.eventsSock);
        assertEquals(17, stats.tunPackets);
        assertEquals(4096, stats.tunBytes);
        assertEquals(250_000, stats.cpuUs);
        assertEquals(2, stats.uids.size());
        assertEquals(10123, stats.uids.get(0).uid);
        assertEquals(5, stats.uids.get(0).sessions);
        assertEquals(40, stats.uids.get(0).events);
    }

    @Test
    public void rejectsMissingOrTruncatedInput() {
        assertNull(PacketLoopStats.parse(null));
        assertNull(PacketLoopStats.parse(new long[0]));
        assertNull(PacketLoopStats.parse(new long[PacketLoopStats.SCALARS - 1]));
    }

    @Test
    public void ignoresUidEntriesTheArrayDoesNotActuallyContain() {
        // A declared count larger than the payload must not throw: reading stats
        // is diagnostics and may never take the service down.
        long[] input = raw(60_000, 10, 1_000);
        input[15] = 5;

        PacketLoopStats stats = PacketLoopStats.parse(input);
        assertNotNull(stats);
        assertTrue(stats.uids.isEmpty());
    }

    @Test
    public void ratesAreScaledToOneHour() {
        PacketLoopStats stats = PacketLoopStats.parse(raw(1_800_000, 900, 30_000_000));

        assertNotNull(stats);
        assertEquals(1800d, stats.wakeupsPerHour(), 0.001);
        assertEquals(60d, stats.cpuSecondsPerHour(), 0.001);
    }

    @Test
    public void ratesAreZeroBeforeTheLoopHasRun() {
        PacketLoopStats stats = PacketLoopStats.parse(raw(0, 0, 0));

        assertNotNull(stats);
        assertEquals(0d, stats.wakeupsPerHour(), 0.0);
        assertEquals(0d, stats.cpuSecondsPerHour(), 0.0);
        assertTrue(stats.format(null, false).contains("no measurement yet"));
    }

    @Test
    public void reportSplitsSystemFromUserApps() {
        FakeUidInfo info = new FakeUidInfo();
        info.labels.put(10123, "Google Play Store");
        info.system.put(10123, true);
        info.labels.put(10456, "Some App");
        info.system.put(10456, false);

        PacketLoopStats stats = PacketLoopStats.parse(
                raw(3_600_000, 400, 8_000_000,
                        new long[]{10123, 30, 270},
                        new long[]{10456, 10, 90}));

        assertNotNull(stats);
        String report = stats.format(info, true);
        assertTrue(report.contains("system apps routed: yes"));
        assertTrue(report.contains("attributed to system apps: 30 sessions, 270 socket events"));
        assertTrue(report.contains("attributed to user apps: 10 sessions, 90 socket events"));
        assertTrue(report.contains("system share of attributed work: 75.0%"));
        assertTrue(report.contains("Google Play Store [system]"));
        assertTrue(report.contains("wakeups: 400 (400/h)"));
    }

    @Test
    public void reportOrdersUidsByTotalWork() {
        FakeUidInfo info = new FakeUidInfo();
        PacketLoopStats stats = PacketLoopStats.parse(
                raw(60_000, 10, 1_000,
                        new long[]{10001, 1, 1},
                        new long[]{10002, 50, 50}));

        assertNotNull(stats);
        String report = stats.format(info, false);
        assertTrue(report.indexOf("uid 10002") < report.indexOf("uid 10001"));
        assertTrue(report.contains("system apps routed: no"));
    }

    @Test
    public void reportFlagsUidTableOverflow() {
        long[] input = raw(60_000, 10, 1_000);
        input[14] = 42;

        PacketLoopStats stats = PacketLoopStats.parse(input);
        assertNotNull(stats);
        assertTrue(stats.format(null, false)
                .contains("UID table overflow (updates dropped): 42"));
    }

    @Test
    public void summaryIsOneLineAndNamesTheArm() {
        PacketLoopStats stats = PacketLoopStats.parse(raw(7_200_000, 1_000, 20_000_000));

        assertNotNull(stats);
        String summary = stats.summary(true);
        assertEquals(-1, summary.indexOf('\n'));
        assertTrue(summary.contains("system routed=true"));
        assertTrue(summary.contains("over 2h00m"));
        assertTrue(summary.contains("wakeups=1000 (500/h)"));
    }

    @Test
    public void formatsDurations() {
        assertEquals("0s", PacketLoopStats.duration(0));
        assertEquals("45s", PacketLoopStats.duration(45_000));
        assertEquals("5m30s", PacketLoopStats.duration(330_000));
        assertEquals("3h05m", PacketLoopStats.duration(11_100_000));
    }
}
