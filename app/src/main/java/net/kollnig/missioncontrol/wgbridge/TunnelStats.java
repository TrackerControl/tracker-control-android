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
    public final long deliveredRxBytes;
    public final long probeReplyToken;

    TunnelStats(long rxBytes, long txBytes, long latestHandshakeMillis) {
        this(rxBytes, txBytes, latestHandshakeMillis, 0L, 0L);
    }

    TunnelStats(long rxBytes, long txBytes, long latestHandshakeMillis,
                long tunWriteFailuresTotal, long tunWriteFailuresStreak) {
        this(rxBytes, txBytes, latestHandshakeMillis, tunWriteFailuresTotal, tunWriteFailuresStreak, 0L);
    }

    TunnelStats(long rxBytes, long txBytes, long latestHandshakeMillis,
                long tunWriteFailuresTotal, long tunWriteFailuresStreak, long deliveredRxBytes) {
        this(rxBytes, txBytes, latestHandshakeMillis, tunWriteFailuresTotal, tunWriteFailuresStreak, deliveredRxBytes, 0L);
    }

    TunnelStats(long rxBytes, long txBytes, long latestHandshakeMillis,
                long tunWriteFailuresTotal, long tunWriteFailuresStreak, long deliveredRxBytes, long probeReplyToken) {
        this.rxBytes = rxBytes;
        this.txBytes = txBytes;
        this.latestHandshakeMillis = latestHandshakeMillis;
        this.tunWriteFailuresTotal = tunWriteFailuresTotal;
        this.tunWriteFailuresStreak = tunWriteFailuresStreak;
        this.deliveredRxBytes = deliveredRxBytes;
        this.probeReplyToken = probeReplyToken;
    }

    public long getRxBytes() {
        return rxBytes;
    }

    public long getDeliveredRxBytes() {
        return deliveredRxBytes;
    }

    public long getProbeReplyToken() {
        return probeReplyToken;
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
