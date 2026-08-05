package eu.faircode.netguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class NetworkReloadPolicyTest {
    @Test
    public void activeNetworkAvailableReloads() {
        assertEquals("network available", NetworkReloadPolicy.onNetworkAvailable());
    }

    @Test
    public void activeNetworkLostReloads() {
        assertEquals("network lost", NetworkReloadPolicy.onNetworkLost("wifi", "wifi"));
    }

    @Test
    public void inactiveNetworkLostDoesNotReload() {
        assertNull(NetworkReloadPolicy.onNetworkLost("mobile", "wifi"));
    }

    @Test
    public void activeNetworkIdentityChangeReloads() {
        assertEquals("Network changed",
                NetworkReloadPolicy.onCapabilitiesChanged(
                        "mobile", "wifi",
                        true, true,
                        false, false));
    }

    @Test
    public void firstCapabilitiesCallbackReloadsAsNetworkChange() {
        assertEquals("Network changed",
                NetworkReloadPolicy.onCapabilitiesChanged(
                        "wifi", null,
                        null, true,
                        null, false));
    }

    @Test
    public void connectedStateChangeReloads() {
        assertEquals("Connected state changed",
                NetworkReloadPolicy.onCapabilitiesChanged(
                        "wifi", "wifi",
                        false, true,
                        false, false));
    }

    @Test
    public void meteredStateChangeReloads() {
        assertEquals("Metered state changed",
                NetworkReloadPolicy.onCapabilitiesChanged(
                        "wifi", "wifi",
                        true, true,
                        false, true));
    }

    @Test
    public void sameCapabilitiesDoNotReload() {
        assertNull(NetworkReloadPolicy.onCapabilitiesChanged(
                "mobile", "mobile",
                true, true,
                true, true));
    }

    @Test
    public void dnsChangeReloadsOnModernAndroid() {
        assertEquals("link properties changed",
                NetworkReloadPolicy.onLinkPropertiesChanged(
                        Collections.singletonList("9.9.9.9"),
                        Collections.singletonList("1.1.1.1"),
                        true,
                        false,
                        null, null));
    }

    @Test
    public void sameDnsDoesNotReloadOnModernAndroid() {
        assertNull(NetworkReloadPolicy.onLinkPropertiesChanged(
                Arrays.asList("9.9.9.9", "149.112.112.112"),
                Arrays.asList("9.9.9.9", "149.112.112.112"),
                true,
                false,
                null, null));
    }

    @Test
    public void preOConnectivityPreferenceControlsLinkPropertyReload() {
        assertEquals("link properties changed",
                NetworkReloadPolicy.onLinkPropertiesChanged(
                        Collections.singletonList("9.9.9.9"),
                        Collections.singletonList("9.9.9.9"),
                        false,
                        true,
                        null, null));

        assertNull(NetworkReloadPolicy.onLinkPropertiesChanged(
                Collections.singletonList("9.9.9.9"),
                Collections.singletonList("1.1.1.1"),
                false,
                false,
                null, null));
    }

    /**
     * Pinning Private DNS to a hostname leaves the resolver list untouched, so
     * comparing DNS servers alone never notices it and the warning that DoT is
     * blocked would not appear until some unrelated network change.
     */
    @Test
    public void privateDnsPinnedReloads() {
        assertEquals("private DNS changed",
                NetworkReloadPolicy.onLinkPropertiesChanged(
                        Collections.singletonList("9.9.9.9"),
                        Collections.singletonList("9.9.9.9"),
                        true,
                        false,
                        null, "dns.google"));
    }

    @Test
    public void privateDnsClearedReloads() {
        assertEquals("private DNS changed",
                NetworkReloadPolicy.onLinkPropertiesChanged(
                        Collections.singletonList("9.9.9.9"),
                        Collections.singletonList("9.9.9.9"),
                        true,
                        false,
                        "dns.google", null));
    }

    @Test
    public void samePrivateDnsDoesNotReload() {
        assertNull(NetworkReloadPolicy.onLinkPropertiesChanged(
                Collections.singletonList("9.9.9.9"),
                Collections.singletonList("9.9.9.9"),
                true,
                false,
                "dns.google", "dns.google"));
    }

    /**
     * The tunnel is unaffected by a resolver being pinned, so this reload must
     * not cost a WireGuard rebind and re-handshake.
     */
    @Test
    public void privateDnsChangeDoesNotRestartWireGuard() {
        assertFalse(NetworkReloadPolicy.shouldRestartWireGuard("private DNS changed"));
    }

    /**
     * A burst of callbacks is collapsed to its last reason, but the rebind it
     * needs is not a property of that reason alone: a private DNS change
     * landing right after a genuine network change must not cancel the rebind
     * that change required, or the tunnel keeps a socket bound to a gone
     * network until some later event.
     */
    @Test
    public void privateDnsChangeDoesNotCancelAPendingRestart() {
        boolean pending = NetworkReloadPolicy.shouldRestartWireGuard(false, "Network changed");
        assertTrue(pending);
        assertTrue(NetworkReloadPolicy.shouldRestartWireGuard(pending, "private DNS changed"));
    }

    @Test
    public void privateDnsChangeAloneStillDoesNotRestartWireGuard() {
        assertFalse(NetworkReloadPolicy.shouldRestartWireGuard(false, "private DNS changed"));
    }

    @Test
    public void physicalConnectivityReloadsRestartWireGuard() {
        assertTrue(NetworkReloadPolicy.shouldRestartWireGuard("network available"));
        assertTrue(NetworkReloadPolicy.shouldRestartWireGuard("network lost"));
        assertTrue(NetworkReloadPolicy.shouldRestartWireGuard("Network changed"));
        assertTrue(NetworkReloadPolicy.shouldRestartWireGuard("Connected state changed"));
        assertTrue(NetworkReloadPolicy.shouldRestartWireGuard("Metered state changed"));
    }

    @Test
    public void linkPropertyReloadRestartsWireGuard() {
        assertTrue(NetworkReloadPolicy.shouldRestartWireGuard("link properties changed"));
    }

    @Test
    public void fallbackConnectivityReloadsRestartWireGuard() {
        assertEquals("connectivity changed", NetworkReloadPolicy.onConnectivityChanged());
        assertTrue(NetworkReloadPolicy.shouldRestartWireGuard("connectivity changed"));
    }
}
