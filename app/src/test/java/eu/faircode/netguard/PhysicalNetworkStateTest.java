package eu.faircode.netguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Shadows;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowNetwork;
import org.robolectric.shadows.ShadowNetworkCapabilities;

import java.net.InetAddress;
import java.lang.reflect.Field;
import java.util.Collections;

@RunWith(RobolectricTestRunner.class)
public class PhysicalNetworkStateTest {
    private static final Network WIFI = ShadowNetwork.newInstance(101);
    private static final Network CELL = ShadowNetwork.newInstance(102);
    private static final Network VPN = ShadowNetwork.newInstance(103);

    @Test
    @org.robolectric.annotation.Config(sdk = 24)
    public void olderAndroidStoresIndependentCallbackSnapshots() throws Exception {
        PhysicalNetworkState state = new PhysicalNetworkState();
        NetworkCapabilities caps = capabilities(NetworkCapabilities.TRANSPORT_WIFI);
        state.onPhysicalCapabilitiesChanged(WIFI, caps);
        LinkProperties props = linkProperties("9.9.9.9");
        state.onPhysicalLinkPropertiesChanged(WIFI, props);
        props.setDnsServers(Collections.singleton(InetAddress.getByName("1.1.1.1")));
        assertEquals(NetworkReloadPolicy.REASON_LINK_PROPERTIES_CHANGED,
                state.onPhysicalLinkPropertiesChanged(WIFI, props));
    }

    private static NetworkCapabilities capabilities(int transport) {
        NetworkCapabilities capabilities = ShadowNetworkCapabilities.newInstance();
        ShadowNetworkCapabilities shadow = Shadows.shadowOf(capabilities);
        shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN);
        shadow.addTransportType(transport);
        return capabilities;
    }

    private static LinkProperties linkProperties(String dns) throws Exception {
        LinkProperties properties = new LinkProperties();
        properties.setDnsServers(Collections.singleton(InetAddress.getByName(dns)));
        return properties;
    }

    @Test
    public void vpnDefaultTransportHandoverReloadsWithoutVpnIdentityChurn() throws Exception {
        PhysicalNetworkState state = new PhysicalNetworkState();
        NetworkCapabilities wifiVpn = capabilities(NetworkCapabilities.TRANSPORT_WIFI);
        Shadows.shadowOf(wifiVpn).removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN);
        Shadows.shadowOf(wifiVpn).addTransportType(NetworkCapabilities.TRANSPORT_VPN);
        assertNull(state.onDefaultNetworkCapabilitiesChanged(VPN, wifiVpn));

        NetworkCapabilities cellVpn = capabilities(NetworkCapabilities.TRANSPORT_CELLULAR);
        Shadows.shadowOf(cellVpn).removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN);
        Shadows.shadowOf(cellVpn).addTransportType(NetworkCapabilities.TRANSPORT_VPN);
        assertEquals(NetworkReloadPolicy.REASON_NETWORK_CHANGED,
                state.onDefaultNetworkCapabilitiesChanged(VPN, cellVpn));

        Network replacement = ShadowNetwork.newInstance(104);
        assertNull(state.onDefaultNetworkAvailable(replacement));
        assertNull(state.onDefaultNetworkCapabilitiesChanged(replacement, cellVpn));
        assertNull(state.onDefaultNetworkLinkPropertiesChanged(replacement,
                linkProperties("9.9.9.9")));
        assertNull(state.onDefaultNetworkLost(VPN));
    }

    @Test
    public void physicalCallbacksWorkWhileVpnIsDefault() {
        PhysicalNetworkState state = new PhysicalNetworkState();

        assertEquals(NetworkReloadPolicy.REASON_NETWORK_AVAILABLE,
                state.onPhysicalAvailable(WIFI));
        assertEquals(NetworkReloadPolicy.REASON_NETWORK_CHANGED,
                state.onPhysicalCapabilitiesChanged(WIFI, capabilities(
                        NetworkCapabilities.TRANSPORT_WIFI)));
        assertNull(state.onPhysicalAvailable(WIFI));
        assertNull(state.getDefaultNetwork());
    }

    @Test
    public void defaultSwitchIsDetectedWhenBothPhysicalNetworksRemain() {
        PhysicalNetworkState state = new PhysicalNetworkState();
        state.onPhysicalAvailable(WIFI);
        state.onPhysicalCapabilitiesChanged(WIFI, capabilities(NetworkCapabilities.TRANSPORT_WIFI));
        state.onPhysicalAvailable(CELL);
        state.onPhysicalCapabilitiesChanged(CELL, capabilities(NetworkCapabilities.TRANSPORT_CELLULAR));

        assertNull(state.onDefaultNetworkAvailable(WIFI));
        assertNull(state.onDefaultNetworkCapabilitiesChanged(WIFI,
                capabilities(NetworkCapabilities.TRANSPORT_WIFI)));
        assertEquals(NetworkReloadPolicy.REASON_NETWORK_CHANGED,
                state.onDefaultNetworkAvailable(CELL));
        assertEquals(CELL, state.getDefaultNetwork());
    }

    @Test
    public void defaultPhysicalTransportChangeReloads() {
        PhysicalNetworkState state = new PhysicalNetworkState();
        state.onPhysicalAvailable(WIFI);
        state.onPhysicalCapabilitiesChanged(WIFI, capabilities(NetworkCapabilities.TRANSPORT_WIFI));
        state.onDefaultNetworkAvailable(WIFI);
        state.onDefaultNetworkCapabilitiesChanged(WIFI,
                capabilities(NetworkCapabilities.TRANSPORT_WIFI));

        assertEquals(NetworkReloadPolicy.REASON_NETWORK_CHANGED,
                state.onDefaultNetworkCapabilitiesChanged(WIFI,
                        capabilities(NetworkCapabilities.TRANSPORT_CELLULAR)));
        assertEquals(WIFI, state.getDefaultNetwork());
    }

    @Test
    public void signalAndBandwidthChatterDoesNotReload() throws Exception {
        PhysicalNetworkState state = new PhysicalNetworkState();
        state.onPhysicalAvailable(CELL);
        NetworkCapabilities initial = capabilities(NetworkCapabilities.TRANSPORT_CELLULAR);
        state.onPhysicalCapabilitiesChanged(CELL, initial);

        NetworkCapabilities chatter = new NetworkCapabilities(initial);
        ShadowNetworkCapabilities chatterShadow = Shadows.shadowOf(chatter);
        chatterShadow.setLinkDownstreamBandwidthKbps(12000);
        chatterShadow.setLinkUpstreamBandwidthKbps(3000);
        setSignalStrength(chatter, -55);
        assertNull(state.onPhysicalCapabilitiesChanged(CELL, chatter));
    }

    @Test
    public void standbyLossIsTrackedAndStaleLossIsIgnored() {
        PhysicalNetworkState state = new PhysicalNetworkState();
        state.onPhysicalAvailable(WIFI);
        state.onPhysicalCapabilitiesChanged(WIFI, capabilities(NetworkCapabilities.TRANSPORT_WIFI));
        state.onPhysicalAvailable(CELL);
        state.onPhysicalCapabilitiesChanged(CELL, capabilities(NetworkCapabilities.TRANSPORT_CELLULAR));

        assertEquals(NetworkReloadPolicy.REASON_NETWORK_LOST,
                state.onPhysicalLost(CELL));
        assertNull(state.onPhysicalLost(CELL));
        assertNull(state.onPhysicalAvailable(WIFI));
    }

    @Test
    public void privateDnsOnlyChangeDoesNotRestartWireGuard() throws Exception {
        PhysicalNetworkState state = new PhysicalNetworkState();
        state.onPhysicalAvailable(WIFI);
        state.onPhysicalCapabilitiesChanged(WIFI, capabilities(NetworkCapabilities.TRANSPORT_WIFI));
        LinkProperties initial = linkProperties("9.9.9.9");
        state.onPhysicalLinkPropertiesChanged(WIFI, initial);

        LinkProperties pinned = linkProperties("9.9.9.9");
        setPrivateDns(pinned, "dns.example", true);
        String change = state.onPhysicalLinkPropertiesChanged(WIFI, pinned);
        assertEquals(NetworkReloadPolicy.REASON_PRIVATE_DNS_CHANGED, change);
        assertFalse(NetworkReloadPolicy.shouldRestartWireGuard(change));
    }

    @Test
    public void vpnDefaultCallbacksDoNotCreatePhysicalDefault() throws Exception {
        PhysicalNetworkState state = new PhysicalNetworkState();
        state.onPhysicalAvailable(WIFI);
        state.onPhysicalCapabilitiesChanged(WIFI, capabilities(NetworkCapabilities.TRANSPORT_WIFI));
        state.onDefaultNetworkAvailable(WIFI);
        state.onDefaultNetworkCapabilitiesChanged(WIFI, capabilities(NetworkCapabilities.TRANSPORT_WIFI));

        NetworkCapabilities vpn = ShadowNetworkCapabilities.newInstance();
        ShadowNetworkCapabilities vpnShadow = Shadows.shadowOf(vpn);
        vpnShadow.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        vpnShadow.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN);
        vpnShadow.addTransportType(NetworkCapabilities.TRANSPORT_VPN);
        assertNull(state.onDefaultNetworkAvailable(VPN));
        assertNull(state.onDefaultNetworkCapabilitiesChanged(VPN, vpn));
        assertNull(state.onDefaultNetworkLinkPropertiesChanged(VPN,
                linkProperties("1.1.1.1")));
        assertNull(state.onDefaultNetworkLost(VPN));
        assertEquals(WIFI, state.getDefaultNetwork());
    }

    private static void setPrivateDns(LinkProperties properties, String name, boolean active)
            throws Exception {
        Field nameField = LinkProperties.class.getDeclaredField("mPrivateDnsServerName");
        nameField.setAccessible(true);
        nameField.set(properties, name);
    }

    private static void setSignalStrength(NetworkCapabilities capabilities, int strength)
            throws Exception {
        Field signalField = NetworkCapabilities.class.getDeclaredField("mSignalStrength");
        signalField.setAccessible(true);
        signalField.setInt(capabilities, strength);
    }
}
