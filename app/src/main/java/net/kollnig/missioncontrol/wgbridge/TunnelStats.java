package net.kollnig.missioncontrol.wgbridge;

/**
 * Snapshot of a tunnel's transfer counters and newest handshake.
 */
public final class TunnelStats {
    public final long rxBytes;
    public final long txBytes;
    public final long latestHandshakeMillis;
    public final long tunWriteFailuresTotal;
    public final long tunWriteFailuresStreak;

    TunnelStats(long rxBytes, long txBytes, long latestHandshakeMillis) {
        this(rxBytes, txBytes, latestHandshakeMillis, 0L, 0L);
    }

    TunnelStats(long rxBytes, long txBytes, long latestHandshakeMillis,
                long tunWriteFailuresTotal, long tunWriteFailuresStreak) {
        this.rxBytes = rxBytes;
        this.txBytes = txBytes;
        this.latestHandshakeMillis = latestHandshakeMillis;
        this.tunWriteFailuresTotal = tunWriteFailuresTotal;
        this.tunWriteFailuresStreak = tunWriteFailuresStreak;
    }

    public long getRxBytes() {
        return rxBytes;
    }

    public long getTxBytes() {
        return txBytes;
    }

    public long getLatestHandshakeMillis() {
        return latestHandshakeMillis;
    }

    public long getTunWriteFailuresTotal() {
        return tunWriteFailuresTotal;
    }

    public long getTunWriteFailuresStreak() {
        return tunWriteFailuresStreak;
    }
}
