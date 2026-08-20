package net.kollnig.missioncontrol.wgbridge;

/**
 * Receives DNS answers observed on decrypted inbound packets and exposes the
 * DNS policy used when a response is sent back to the app. Called from native
 * tunnel threads.
 */
public interface DnsRecorder {
    void recordDns(String qname, String aname, String resource, int ttl);

    /**
     * Returns whether a response for {@code qname} should be returned without
     * answers. The current app policy is intentionally a no-op.
     */
    default boolean isDomainBlocked(String qname) {
        return false;
    }

    /** RCODE for a response blanked by the DNS policy (NXDOMAIN by default). */
    default int blockedRcode() {
        return 3;
    }
}
