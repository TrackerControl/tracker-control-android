package net.kollnig.missioncontrol;

/** Compatibility visibility helper retained for JVM coverage while Timeline uses Compose. */
public final class TimelineHintAdapter {
    private TimelineHintAdapter() {
    }

    public static boolean shouldShow(boolean hasEntries, boolean hintEnabled) {
        return hasEntries && hintEnabled;
    }
}
