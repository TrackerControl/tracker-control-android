package net.kollnig.missioncontrol.wgbridge;

/**
 * Handle to a running gotatun tunnel. {@link #stop()} must be called from
 * Java when the VpnService is torn down; all other methods throw
 * {@link RuntimeException} once the tunnel is stopped.
 */
public final class Tunnel {
    private long handle;

    Tunnel(long handle) {
        this.handle = handle;
    }

    /**
     * Reapplies UAPI configuration to the running device without restarting it.
     */
    public synchronized void setConfig(String uapiConfig) {
        nativeSetConfig(handle, uapiConfig);
    }

    /**
     * Snapshot of the device's transfer counters and newest handshake, summed
     * across all peers. Engine rxBytes includes handshakes; deliveredRxBytes
     * counts only complete decrypted IP packets successfully written to Android.
     */
    public synchronized TunnelStats stats() {
        long[] values = nativeStats(handle);
        long totalFailures = values.length > 3 ? values[3] : 0L;
        long failureStreak = values.length > 4 ? values[4] : 0L;
        long deliveredRx = values.length > 5 ? values[5] : 0L;
        long probeReply = values.length > 6 ? values[6] : 0L;
        return new TunnelStats(values[0], values[1], values[2], totalFailures, failureStreak, deliveredRx, probeReply);
    }

    /**
     * Returns the newest peer handshake timestamp in epoch millis.
     */
    public synchronized long latestHandshakeMillis() {
        return stats().latestHandshakeMillis;
    }

    /**
     * Prods a stalled tunnel into generating traffic (and re-handshaking if
     * the session expired). Asynchronous; returns immediately.
     */
    public synchronized void sendKeepalive() {
        nativeSendKeepalive(handle);
    }

    /**
     * Closes and re-binds the outer UDP sockets, re-protecting the new
     * sockets via the Protector callback. Call after the default network
     * changed so encrypted traffic leaves via the new network.
     */
    public synchronized void rebind() {
        nativeRebind(handle);
    }

    /** Queues a DNS reachability probe inside WireGuard. No direct socket send. */
    public synchronized boolean sendDnsProbe(String sourceIp, String resolverIp, long token) {
        return nativeSendDnsProbe(handle, sourceIp, resolverIp, token);
    }

    /**
     * Moves a peer to a new endpoint ("ip:port" or "[ipv6]:port", already
     * resolved) without disturbing the session, e.g. after DNS re-resolution.
     */
    public synchronized void updateEndpoint(String peerPublicKeyBase64, String endpoint) {
        nativeUpdateEndpoint(handle, peerPublicKeyBase64, endpoint);
    }

    /**
     * Sets one peer's persistent keepalive interval in seconds (0 disables
     * it) without disturbing the session. This is how the screen-state
     * keepalive policy is applied; {@link #setConfig} would re-serialise the
     * whole configuration, endpoint resolution included, for a single field.
     */
    public synchronized void setKeepalive(String peerPublicKeyBase64, int seconds) {
        nativeSetKeepalive(handle, peerPublicKeyBase64, seconds);
    }

    /**
     * Tears down gotatun and closes the duplicated fds. Idempotent.
     */
    public synchronized void stop() {
        long h = handle;
        handle = 0;
        if (h != 0)
            nativeStop(h);
    }

    private static native void nativeSetConfig(long handle, String uapiConfig);

    private static native long[] nativeStats(long handle);

    private static native void nativeSendKeepalive(long handle);

    private static native void nativeRebind(long handle);

    private static native boolean nativeSendDnsProbe(long handle, String sourceIp, String resolverIp, long token);

    private static native void nativeUpdateEndpoint(long handle, String peerPublicKeyBase64, String endpoint);

    private static native void nativeSetKeepalive(long handle, String peerPublicKeyBase64, int seconds);

    private static native void nativeStop(long handle);
}
