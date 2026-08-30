package net.kollnig.missioncontrol;

/** Compatibility state helper retained for JVM coverage while Timeline uses Compose. */
public final class TimelineEmptyAdapter {
    private TimelineEmptyAdapter() {
    }

    public enum EmptyState {
        TRACKER_CONTROL_OFF,
        RECORDING_OFF,
        RECORDING_UNAVAILABLE,
        WATCHING
    }

    public static EmptyState stateFor(boolean trackerControlEnabled,
                                      boolean trackerRecordingEnabled) {
        return stateFor(trackerControlEnabled, trackerRecordingEnabled, true);
    }

    public static EmptyState stateFor(boolean trackerControlEnabled,
                                      boolean trackerRecordingEnabled,
                                      boolean trackerRecordingAvailable) {
        if (!trackerControlEnabled)
            return EmptyState.TRACKER_CONTROL_OFF;
        if (!trackerRecordingAvailable)
            return EmptyState.RECORDING_UNAVAILABLE;
        if (!trackerRecordingEnabled)
            return EmptyState.RECORDING_OFF;
        return EmptyState.WATCHING;
    }
}
