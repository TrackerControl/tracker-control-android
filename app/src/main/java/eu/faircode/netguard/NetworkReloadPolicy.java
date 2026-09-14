package eu.faircode.netguard;

final class NetworkReloadPolicy {
    static final String REASON_NETWORK_CHANGED = "Network changed";
    static final String REASON_LINK_PROPERTIES_CHANGED = "link properties changed";
    static final String REASON_PRIVATE_DNS_CHANGED = "private DNS changed";
    static final String REASON_METERED_CHANGED = "Metered state changed";
    static final String REASON_DNS_CHANGED = "DNS servers changed";
    static final String REASON_CONNECTIVITY_CHANGED = "connectivity changed";

    private NetworkReloadPolicy() { }

    static String onConnectivityChanged() {
        return REASON_CONNECTIVITY_CHANGED;
    }

    static boolean shouldRestartWireGuard(String reason) {
        return REASON_NETWORK_CHANGED.equals(reason) ||
                REASON_LINK_PROPERTIES_CHANGED.equals(reason) ||
                REASON_CONNECTIVITY_CHANGED.equals(reason);
    }
}
