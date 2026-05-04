package eu.faircode.netguard;

import static org.junit.Assert.assertEquals;
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
                        false));
    }

    @Test
    public void sameDnsDoesNotReloadOnModernAndroid() {
        assertNull(NetworkReloadPolicy.onLinkPropertiesChanged(
                Arrays.asList("9.9.9.9", "149.112.112.112"),
                Arrays.asList("9.9.9.9", "149.112.112.112"),
                true,
                false));
    }

    @Test
    public void preOConnectivityPreferenceControlsLinkPropertyReload() {
        assertEquals("link properties changed",
                NetworkReloadPolicy.onLinkPropertiesChanged(
                        Collections.singletonList("9.9.9.9"),
                        Collections.singletonList("9.9.9.9"),
                        false,
                        true));

        assertNull(NetworkReloadPolicy.onLinkPropertiesChanged(
                Collections.singletonList("9.9.9.9"),
                Collections.singletonList("1.1.1.1"),
                false,
                false));
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
