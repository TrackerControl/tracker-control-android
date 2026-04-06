package net.kollnig.missioncontrol.data;

import java.util.List;

/**
 * A timeline entry grouping all recent tracker contacts for one app.
 * Each entry contains the tracker companies contacted, with their blocked/allowed state.
 */
public class TimelineEntry {
    public final int uid;
    public final String appName;
    public final String packageName;
    public final long mostRecentTime;
    public final List<TrackerContact> trackers;

    public TimelineEntry(int uid, String appName, String packageName,
                         long mostRecentTime, List<TrackerContact> trackers) {
        this.uid = uid;
        this.appName = appName;
        this.packageName = packageName;
        this.mostRecentTime = mostRecentTime;
        this.trackers = trackers;
    }

    public int getBlockedCount() {
        int count = 0;
        for (TrackerContact tc : trackers)
            if (tc.blocked) count++;
        return count;
    }

    public int getAllowedCount() {
        int count = 0;
        for (TrackerContact tc : trackers)
            if (!tc.blocked) count++;
        return count;
    }

    public boolean hasMixed() {
        return getBlockedCount() > 0 && getAllowedCount() > 0;
    }
}
