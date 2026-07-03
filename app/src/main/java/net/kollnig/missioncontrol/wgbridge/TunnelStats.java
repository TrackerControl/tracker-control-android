package net.kollnig.missioncontrol.wgbridge;

/**
 * Snapshot of a tunnel's transfer counters and newest handshake.
 */
public final class TunnelStats {
    public final long rxBytes;
    public final long txBytes;
    public final long latestHandshakeMillis;

    TunnelStats(long rxBytes, long txBytes, long latestHandshakeMillis) {
        this.rxBytes = rxBytes;
        this.txBytes = txBytes;
        this.latestHandshakeMillis = latestHandshakeMillis;
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
}
