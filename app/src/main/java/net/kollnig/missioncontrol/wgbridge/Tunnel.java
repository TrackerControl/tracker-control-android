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
     * across all peers. rx bytes count decrypted transport payload, so they
     * only advance when the tunnel actually carries return traffic — that is
     * the liveness signal the connectivity monitor is biased toward.
     */
    public synchronized TunnelStats stats() {
        long[] values = nativeStats(handle);
        return new TunnelStats(values[0], values[1], values[2]);
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

    /**
     * Moves a peer to a new endpoint ("ip:port" or "[ipv6]:port", already
     * resolved) without disturbing the session, e.g. after DNS re-resolution.
     */
    public synchronized void updateEndpoint(String peerPublicKeyBase64, String endpoint) {
        nativeUpdateEndpoint(handle, peerPublicKeyBase64, endpoint);
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

    private static native void nativeUpdateEndpoint(long handle, String peerPublicKeyBase64, String endpoint);

    private static native void nativeStop(long handle);
}
