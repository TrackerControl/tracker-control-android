package eu.faircode.netguard;

import static org.junit.Assert.*;
import org.junit.Test;

public class NetworkReloadPolicyTest {
    @Test
    public void onlyPathChangesRequestWireGuardRecreation() {
        assertTrue(NetworkReloadPolicy.shouldRestartWireGuard(NetworkReloadPolicy.REASON_NETWORK_CHANGED));
        assertTrue(NetworkReloadPolicy.shouldRestartWireGuard(NetworkReloadPolicy.REASON_LINK_PROPERTIES_CHANGED));
        assertTrue(NetworkReloadPolicy.shouldRestartWireGuard(NetworkReloadPolicy.onConnectivityChanged()));
        assertFalse(NetworkReloadPolicy.shouldRestartWireGuard(NetworkReloadPolicy.REASON_METERED_CHANGED));
        assertFalse(NetworkReloadPolicy.shouldRestartWireGuard(NetworkReloadPolicy.REASON_DNS_CHANGED));
        assertFalse(NetworkReloadPolicy.shouldRestartWireGuard(NetworkReloadPolicy.REASON_PRIVATE_DNS_CHANGED));
        assertFalse(NetworkReloadPolicy.shouldRestartWireGuard(null));
    }
}
