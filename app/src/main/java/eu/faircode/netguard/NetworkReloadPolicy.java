package eu.faircode.netguard;

import java.util.List;
import java.util.Objects;

final class NetworkReloadPolicy {
    static final String REASON_NETWORK_AVAILABLE = "network available";
    static final String REASON_NETWORK_LOST = "network lost";
    static final String REASON_NETWORK_CHANGED = "Network changed";
    static final String REASON_CONNECTED_CHANGED = "Connected state changed";
    static final String REASON_LINK_PROPERTIES_CHANGED = "link properties changed";
    static final String REASON_METERED_CHANGED = "Metered state changed";
    static final String REASON_CONNECTIVITY_CHANGED = "connectivity changed";

    private NetworkReloadPolicy() {
    }

    static String onNetworkAvailable() {
        return REASON_NETWORK_AVAILABLE;
    }

    static String onNetworkLost(Object lostNetwork, Object lastActiveNetwork) {
        return lastActiveNetwork != null && Objects.equals(lastActiveNetwork, lostNetwork)
                ? REASON_NETWORK_LOST
                : null;
    }

    static String onConnectivityChanged() {
        return REASON_CONNECTIVITY_CHANGED;
    }

    static String onLinkPropertiesChanged(List<?> lastDns, List<?> currentDns,
                                          boolean compareDns, boolean reloadOnConnectivity) {
        if (compareDns ? !same(lastDns, currentDns) : reloadOnConnectivity)
            return REASON_LINK_PROPERTIES_CHANGED;

        return null;
    }

    static String onCapabilitiesChanged(Object network, Object lastNetwork,
                                        Boolean lastConnected, boolean connected,
                                        Boolean lastMetered, boolean metered) {
        if (!Objects.equals(network, lastNetwork))
            return REASON_NETWORK_CHANGED;

        if (lastConnected != null && !lastConnected.equals(connected))
            return REASON_CONNECTED_CHANGED;

        if (lastMetered != null && !lastMetered.equals(metered))
            return REASON_METERED_CHANGED;

        return null;
    }

    static boolean shouldRestartWireGuard(String reason) {
        return REASON_NETWORK_AVAILABLE.equals(reason) ||
                REASON_NETWORK_LOST.equals(reason) ||
                REASON_NETWORK_CHANGED.equals(reason) ||
                REASON_CONNECTED_CHANGED.equals(reason) ||
                REASON_LINK_PROPERTIES_CHANGED.equals(reason) ||
                REASON_METERED_CHANGED.equals(reason) ||
                REASON_CONNECTIVITY_CHANGED.equals(reason);
    }

    static boolean same(List<?> last, List<?> current) {
        if (last == null || current == null || last.size() != current.size())
            return false;

        for (int i = 0; i < current.size(); i++)
            if (!Objects.equals(last.get(i), current.get(i)))
                return false;

        return true;
    }
}
