package eu.faircode.netguard;

/*
    This file is part of TrackerControl.

    TrackerControl is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    TrackerControl is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with TrackerControl.  If not, see <http://www.gnu.org/licenses/>.
*/

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Parses and formats the native packet-loop counters exposed by
 * {@code jni_get_loop_stats} (issue #653).
 *
 * <p>The question these counters answer is whether routing system apps through
 * the tun costs battery through <em>wakeup frequency</em> rather than
 * throughput. The primary figures are therefore wakeups per hour and the loop
 * thread's CPU time per hour — never MB/s, which stays low while background
 * chatter from Play Services and friends keeps waking the loop.
 *
 * <p>Deliberately free of Android dependencies so the arithmetic and the report
 * layout are unit-testable on the JVM; callers supply UID metadata through
 * {@link UidInfo}.
 */
public class PacketLoopStats {
    // Index of each scalar in the long[] from the native side.
    // Keep in sync with LOOP_STATS_SCALARS / the writer in netguard.c.
    static final int SCALARS = 16;

    private static final int IDX_ELAPSED_MS = 0;
    private static final int IDX_ITERATIONS = 1;
    private static final int IDX_POLLS = 2;
    private static final int IDX_WAKEUPS = 3;
    private static final int IDX_TIMEOUTS = 4;
    private static final int IDX_RECHECK_POLLS = 5;
    private static final int IDX_EVENTS_TUN = 6;
    private static final int IDX_EVENTS_SOCK = 7;
    private static final int IDX_EVENTS_PIPE = 8;
    private static final int IDX_TUN_PACKETS = 9;
    private static final int IDX_TUN_BYTES = 10;
    private static final int IDX_CPU_US = 11;
    private static final int IDX_SCAN_US = 12;
    private static final int IDX_DISPATCH_US = 13;
    private static final int IDX_UID_OVERFLOW = 14;
    private static final int IDX_UID_COUNT = 15;

    private static final long MS_PER_HOUR = 3600_000L;

    /** How many UIDs to name individually in the report. */
    private static final int TOP_UIDS = 10;

    /** Metadata the caller resolves per UID; the native side only knows numbers. */
    public interface UidInfo {
        /** Human-readable label, e.g. an app name. May be null. */
        String label(int uid);

        /** Whether this UID belongs to a system app, per {@link Rule#system}. */
        boolean isSystem(int uid);
    }

    public static final class UidStat {
        public final int uid;
        public final long sessions;
        public final long events;

        UidStat(int uid, long sessions, long events) {
            this.uid = uid;
            this.sessions = sessions;
            this.events = events;
        }

        long total() {
            return sessions + events;
        }
    }

    public final long elapsedMs;
    public final long iterations;
    public final long polls;
    public final long wakeups;
    public final long timeouts;
    public final long recheckPolls;
    public final long eventsTun;
    public final long eventsSock;
    public final long eventsPipe;
    public final long tunPackets;
    public final long tunBytes;
    public final long cpuUs;
    public final long scanUs;
    public final long dispatchUs;
    public final long uidOverflow;
    public final List<UidStat> uids;

    private PacketLoopStats(long[] raw) {
        this.elapsedMs = raw[IDX_ELAPSED_MS];
        this.iterations = raw[IDX_ITERATIONS];
        this.polls = raw[IDX_POLLS];
        this.wakeups = raw[IDX_WAKEUPS];
        this.timeouts = raw[IDX_TIMEOUTS];
        this.recheckPolls = raw[IDX_RECHECK_POLLS];
        this.eventsTun = raw[IDX_EVENTS_TUN];
        this.eventsSock = raw[IDX_EVENTS_SOCK];
        this.eventsPipe = raw[IDX_EVENTS_PIPE];
        this.tunPackets = raw[IDX_TUN_PACKETS];
        this.tunBytes = raw[IDX_TUN_BYTES];
        this.cpuUs = raw[IDX_CPU_US];
        this.scanUs = raw[IDX_SCAN_US];
        this.dispatchUs = raw[IDX_DISPATCH_US];
        this.uidOverflow = raw[IDX_UID_OVERFLOW];

        List<UidStat> parsed = new ArrayList<>();
        // Trust the array length rather than the declared count: a truncated
        // array must not throw while the caller is only trying to read stats.
        int declared = (int) raw[IDX_UID_COUNT];
        int available = (raw.length - SCALARS) / 3;
        int count = Math.min(Math.max(declared, 0), Math.max(available, 0));
        for (int i = 0; i < count; i++) {
            int base = SCALARS + i * 3;
            parsed.add(new UidStat((int) raw[base], raw[base + 1], raw[base + 2]));
        }
        this.uids = Collections.unmodifiableList(parsed);
    }

    /**
     * @param raw the array returned by {@code jni_get_loop_stats}, or null
     * @return the parsed snapshot, or null when the native side returned
     * nothing usable
     */
    public static PacketLoopStats parse(long[] raw) {
        if (raw == null || raw.length < SCALARS)
            return null;
        return new PacketLoopStats(raw);
    }

    /** Wakeups per hour — the metric the battery hypothesis rests on. */
    public double wakeupsPerHour() {
        return perHour(wakeups);
    }

    /** Loop-thread CPU seconds burned per hour of wall-clock time. */
    public double cpuSecondsPerHour() {
        return perHour(cpuUs) / 1_000_000d;
    }

    private double perHour(long value) {
        if (elapsedMs <= 0)
            return 0d;
        return value * (double) MS_PER_HOUR / elapsedMs;
    }

    /**
     * Renders the human-readable report.
     *
     * @param info           resolves UID labels and system classification
     * @param routingSystem  current value of the {@code include_system_vpn}
     *                       preference, recorded so a report is never read
     *                       without knowing which arm of the comparison it is
     */
    public String format(UidInfo info, boolean routingSystem) {
        StringBuilder sb = new StringBuilder();
        sb.append("Packet loop (issue #653)\n");
        sb.append(String.format(Locale.ROOT, "  system apps routed: %s\n",
                routingSystem ? "yes" : "no"));
        sb.append(String.format(Locale.ROOT, "  measured over: %s\n", duration(elapsedMs)));

        if (elapsedMs <= 0) {
            sb.append("  no measurement yet (packet loop not running)\n");
            return sb.toString();
        }

        sb.append("  wakeups: ").append(wakeups)
                .append(String.format(Locale.ROOT, " (%.0f/h)", wakeupsPerHour())).append('\n');
        sb.append("  epoll timeouts: ").append(timeouts)
                .append(String.format(Locale.ROOT, " (%.0f/h)", perHour(timeouts))).append('\n');
        sb.append("  loop iterations: ").append(iterations);
        if (wakeups > 0)
            sb.append(String.format(Locale.ROOT, " (%.1f per wakeup)", iterations / (double) wakeups));
        sb.append('\n');
        sb.append("  polls capped to 100 ms: ").append(recheckPolls).append('\n');
        sb.append("  events: tun ").append(eventsTun)
                .append(", socket ").append(eventsSock)
                .append(", pipe ").append(eventsPipe).append('\n');
        sb.append("  tun packets: ").append(tunPackets)
                .append(String.format(Locale.ROOT, " (%.0f/h)", perHour(tunPackets)))
                .append(", ").append(tunBytes).append(" bytes\n");
        sb.append(String.format(Locale.ROOT, "  loop thread CPU: %.3f s (%.3f s/h)\n",
                cpuUs / 1_000_000d, cpuSecondsPerHour()));
        sb.append(String.format(Locale.ROOT, "  wall time in loop: scan %.3f s, dispatch %.3f s\n",
                scanUs / 1_000_000d, dispatchUs / 1_000_000d));
        if (wakeups > 0)
            sb.append(String.format(Locale.ROOT, "  CPU per wakeup: %.0f us\n",
                    cpuUs / (double) wakeups));

        appendUidBreakdown(sb, info);
        return sb.toString();
    }

    private void appendUidBreakdown(StringBuilder sb, UidInfo info) {
        long systemSessions = 0;
        long systemEvents = 0;
        long userSessions = 0;
        long userEvents = 0;
        for (UidStat stat : uids)
            if (info != null && info.isSystem(stat.uid)) {
                systemSessions += stat.sessions;
                systemEvents += stat.events;
            } else {
                userSessions += stat.sessions;
                userEvents += stat.events;
            }

        sb.append("  attributed to system apps: ").append(systemSessions)
                .append(" sessions, ").append(systemEvents).append(" socket events\n");
        sb.append("  attributed to user apps: ").append(userSessions)
                .append(" sessions, ").append(userEvents).append(" socket events\n");
        long attributed = systemSessions + systemEvents + userSessions + userEvents;
        if (attributed > 0) {
            double share = 100d * (systemSessions + systemEvents) / attributed;
            sb.append(String.format(Locale.ROOT, "  system share of attributed work: %.1f%%\n",
                    share));
        }
        if (uidOverflow > 0)
            sb.append("  UID table overflow (updates dropped): ").append(uidOverflow).append('\n');

        List<UidStat> sorted = new ArrayList<>(uids);
        Collections.sort(sorted, new Comparator<UidStat>() {
            @Override
            public int compare(UidStat a, UidStat b) {
                int byTotal = Long.compare(b.total(), a.total());
                return byTotal != 0 ? byTotal : Integer.compare(a.uid, b.uid);
            }
        });

        sb.append("  top UIDs (sessions + socket events):\n");
        if (sorted.isEmpty())
            sb.append("    none\n");
        int shown = 0;
        for (UidStat stat : sorted) {
            if (shown++ >= TOP_UIDS)
                break;
            String label = info == null ? null : info.label(stat.uid);
            sb.append(String.format(Locale.ROOT, "    uid %d %s%s: %d sessions, %d socket events\n",
                    stat.uid,
                    label == null || label.isEmpty() ? "?" : label,
                    info != null && info.isSystem(stat.uid) ? " [system]" : "",
                    stat.sessions, stat.events));
        }
        if (sorted.size() > TOP_UIDS)
            sb.append("    ... and ").append(sorted.size() - TOP_UIDS).append(" more\n");
    }

    /** One-line variant for logcat. */
    public String summary(boolean routingSystem) {
        return String.format(Locale.ROOT,
                "Packet loop: system routed=%b over %s, wakeups=%d (%.0f/h), " +
                        "iterations=%d, tun packets=%d, cpu=%.3fs (%.3fs/h)",
                routingSystem, duration(elapsedMs), wakeups, wakeupsPerHour(),
                iterations, tunPackets, cpuUs / 1_000_000d, cpuSecondsPerHour());
    }

    static String duration(long ms) {
        if (ms <= 0)
            return "0s";
        long seconds = ms / 1000;
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        if (hours > 0)
            return String.format(Locale.ROOT, "%dh%02dm", hours, minutes);
        if (minutes > 0)
            return String.format(Locale.ROOT, "%dm%02ds", minutes, seconds % 60);
        return seconds + "s";
    }
}
