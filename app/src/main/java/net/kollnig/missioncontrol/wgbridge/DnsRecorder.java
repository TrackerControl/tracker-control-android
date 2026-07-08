package net.kollnig.missioncontrol.wgbridge;

/**
 * Receives DNS answers observed on decrypted inbound packets. Passive:
 * TrackerControl uses this mapping later when deciding on app connections,
 * but the DNS response is not blocked or rewritten. Called from native
 * tunnel threads.
 */
public interface DnsRecorder {
    void recordDns(String qname, String aname, String resource, int ttl);
}
