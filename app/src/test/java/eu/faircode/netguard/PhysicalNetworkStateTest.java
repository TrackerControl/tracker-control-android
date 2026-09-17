package eu.faircode.netguard;

import static org.junit.Assert.*;

import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Shadows;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowNetwork;
import org.robolectric.shadows.ShadowNetworkCapabilities;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.util.Collections;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
public class PhysicalNetworkStateTest {
    private static final Network WIFI = ShadowNetwork.newInstance(101);
    private static final Network CELL = ShadowNetwork.newInstance(102);
    private static final Network VPN = ShadowNetwork.newInstance(103);

    private static NetworkCapabilities capabilities(int transport) {
        NetworkCapabilities caps = ShadowNetworkCapabilities.newInstance();
        Shadows.shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        Shadows.shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN);
        Shadows.shadowOf(caps).addTransportType(transport);
        return caps;
    }

    private static NetworkCapabilities vpn(int transport) {
        NetworkCapabilities caps = capabilities(transport);
        Shadows.shadowOf(caps).removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN);
        Shadows.shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_VPN);
        return caps;
    }

    private static LinkProperties links(String address, String dns) throws Exception {
        LinkProperties props = new LinkProperties();
        props.setLinkAddresses(Collections.singleton(linkAddress(address)));
        props.setDnsServers(Collections.singleton(InetAddress.getByName(dns)));
        return props;
    }

    private static PhysicalNetworkState wifiWithStandbyCell() throws Exception {
        PhysicalNetworkState state = new PhysicalNetworkState();
        state.onPhysicalAvailable(WIFI);
        state.onPhysicalCapabilitiesChanged(WIFI, capabilities(NetworkCapabilities.TRANSPORT_WIFI));
        state.onPhysicalLinkPropertiesChanged(WIFI, links("192.0.2.2/24", "9.9.9.9"));
        state.onPhysicalAvailable(CELL);
        state.onPhysicalCapabilitiesChanged(CELL, capabilities(NetworkCapabilities.TRANSPORT_CELLULAR));
        state.onPhysicalLinkPropertiesChanged(CELL, links("198.51.100.2/24", "1.1.1.1"));
        assertEquals(NetworkReloadPolicy.REASON_NETWORK_CHANGED,
                state.onDefaultNetworkCapabilitiesChanged(VPN, vpn(NetworkCapabilities.TRANSPORT_WIFI)));
        assertEquals(WIFI, state.getDefaultNetwork());
        return state;
    }

    @Test
    public void standbyChatterAndLossNeverReloadActiveWifi() throws Exception {
        PhysicalNetworkState state = wifiWithStandbyCell();
        NetworkCapabilities cell = capabilities(NetworkCapabilities.TRANSPORT_CELLULAR);
        Shadows.shadowOf(cell).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        Shadows.shadowOf(cell).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED);
        Shadows.shadowOf(cell).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        assertNull(state.onPhysicalCapabilitiesChanged(CELL, cell));
        assertNull(state.onPhysicalLinkPropertiesChanged(CELL, links("198.51.100.3/24", "8.8.8.8")));
        assertNull(state.onPhysicalLost(CELL));
        assertNull(state.onPhysicalLost(CELL));
        assertEquals(WIFI, state.getDefaultNetwork());
    }

    @Test
    public void vpnTransportHandoverSelectsCellAndIgnoresLateWifiLoss() throws Exception {
        PhysicalNetworkState state = wifiWithStandbyCell();
        assertEquals(NetworkReloadPolicy.REASON_NETWORK_CHANGED,
                state.onDefaultNetworkCapabilitiesChanged(VPN, vpn(NetworkCapabilities.TRANSPORT_CELLULAR)));
        assertEquals(CELL, state.getDefaultNetwork());
        assertNull(state.onPhysicalLost(WIFI));
        Network replacement = ShadowNetwork.newInstance(104);
        assertNull(state.onDefaultNetworkAvailable(replacement));
        NetworkCapabilities uninitialised = ShadowNetworkCapabilities.newInstance();
        Shadows.shadowOf(uninitialised).removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN);
        Shadows.shadowOf(uninitialised).addTransportType(NetworkCapabilities.TRANSPORT_VPN);
        assertNull(state.onDefaultNetworkCapabilitiesChanged(replacement, uninitialised));
        assertNull(state.onDefaultNetworkCapabilitiesChanged(replacement, vpn(NetworkCapabilities.TRANSPORT_CELLULAR)));
        assertNull(state.onDefaultNetworkLinkPropertiesChanged(replacement, links("10.0.0.2/32", "10.0.0.1")));
        assertNull(state.onDefaultNetworkLost(VPN));
    }

    @Test
    public void unvalidatedPhysicalDefaultSwitchIsDetected() throws Exception {
        PhysicalNetworkState state = wifiWithStandbyCell();
        assertNull(state.onDefaultNetworkCapabilitiesChanged(WIFI, capabilities(NetworkCapabilities.TRANSPORT_WIFI)));
        assertEquals(NetworkReloadPolicy.REASON_NETWORK_CHANGED,
                state.onDefaultNetworkCapabilitiesChanged(CELL, capabilities(NetworkCapabilities.TRANSPORT_CELLULAR)));
    }

    @Test
    public void sameTransportStandbyDoesNotDisplaceLiveEgress() throws Exception {
        PhysicalNetworkState state = wifiWithStandbyCell();
        Network otherWifi = ShadowNetwork.newInstance(104);
        assertNull(state.onPhysicalAvailable(otherWifi));
        assertNull(state.onPhysicalCapabilitiesChanged(otherWifi, capabilities(NetworkCapabilities.TRANSPORT_WIFI)));
        assertEquals(WIFI, state.getDefaultNetwork());
        assertEquals(NetworkReloadPolicy.REASON_NETWORK_CHANGED, state.onPhysicalLost(WIFI));
        assertEquals(otherWifi, state.getDefaultNetwork());
        assertNull(state.onPhysicalCapabilitiesChanged(WIFI, capabilities(NetworkCapabilities.TRANSPORT_WIFI)));
        assertNull(state.onPhysicalLinkPropertiesChanged(WIFI, links("192.0.2.2/24", "9.9.9.9")));
    }

    @Test
    @Config(sdk = 23)
    public void suppliedDefaultSnapshotSelectsEgressOnApi23() throws Exception {
        PhysicalNetworkState state = wifiWithStandbyCell();
        assertEquals(NetworkReloadPolicy.REASON_NETWORK_CHANGED,
                state.onDefaultNetworkCapabilitiesChanged(VPN, vpn(NetworkCapabilities.TRANSPORT_CELLULAR)));
        assertEquals(CELL, state.getDefaultNetwork());
    }

    @Test
    public void activeValidationSuspensionAndSignalChangesAreIgnored() throws Exception {
        PhysicalNetworkState state = wifiWithStandbyCell();
        NetworkCapabilities wifi = capabilities(NetworkCapabilities.TRANSPORT_WIFI);
        Shadows.shadowOf(wifi).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        Shadows.shadowOf(wifi).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED);
        Shadows.shadowOf(wifi).setLinkDownstreamBandwidthKbps(12000);
        // There is no public setter on all tested SDKs. Fail loudly if the
        // AOSP field changes when updating Robolectric's Android runtime.
        setField(wifi, "mSignalStrength", -55);
        assertNull(state.onPhysicalCapabilitiesChanged(WIFI, wifi));
    }

    @Test
    public void activeMeteredAndDnsChangesReloadPolicyWithoutForcingWireGuard() throws Exception {
        PhysicalNetworkState state = wifiWithStandbyCell();
        NetworkCapabilities wifi = capabilities(NetworkCapabilities.TRANSPORT_WIFI);
        Shadows.shadowOf(wifi).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        String metered = state.onPhysicalCapabilitiesChanged(WIFI, wifi);
        assertEquals(NetworkReloadPolicy.REASON_METERED_CHANGED, metered);
        assertFalse(NetworkReloadPolicy.shouldRestartWireGuard(metered));
        String dns = state.onPhysicalLinkPropertiesChanged(WIFI, links("192.0.2.2/24", "8.8.8.8"));
        assertEquals(NetworkReloadPolicy.REASON_DNS_CHANGED, dns);
        assertFalse(NetworkReloadPolicy.shouldRestartWireGuard(dns));
    }

    @Test
    @Config(sdk = 24)
    public void activeAddressChangesUseIndependentSnapshotsOnOlderAndroid() throws Exception {
        PhysicalNetworkState state = wifiWithStandbyCell();
        LinkProperties props = links("192.0.2.2/24", "9.9.9.9");
        assertNull(state.onPhysicalLinkPropertiesChanged(WIFI, props));
        props.setLinkAddresses(Collections.singleton(linkAddress("192.0.2.3/24")));
        String change = state.onPhysicalLinkPropertiesChanged(WIFI, props);
        assertEquals(NetworkReloadPolicy.REASON_LINK_PROPERTIES_CHANGED, change);
        assertTrue(NetworkReloadPolicy.shouldRestartWireGuard(change));
    }

    @Test
    @Config(sdk = 28)
    public void privateDnsActiveAndHostnameChangesAreBothPolicyOnly() throws Exception {
        PhysicalNetworkState state = wifiWithStandbyCell();
        LinkProperties props = links("192.0.2.2/24", "9.9.9.9");
        // Private DNS setters are hidden framework APIs; use reflection only
        // in the fixture, keeping production on the public getters.
        setField(props, "mUsePrivateDns", true);
        String active = state.onPhysicalLinkPropertiesChanged(WIFI, props);
        assertEquals(NetworkReloadPolicy.REASON_PRIVATE_DNS_CHANGED, active);
        assertFalse(NetworkReloadPolicy.shouldRestartWireGuard(active));
        setField(props, "mPrivateDnsServerName", "dns.example");
        assertEquals(NetworkReloadPolicy.REASON_PRIVATE_DNS_CHANGED,
                state.onPhysicalLinkPropertiesChanged(WIFI, props));
        assertNull(state.onPhysicalLinkPropertiesChanged(WIFI, props));
    }

    @Test
    public void defaultOnlyNetworksCannotAccumulatePhysicalEntries() throws Exception {
        PhysicalNetworkState state = new PhysicalNetworkState();
        for (int id = 200; id < 220; id++) {
            Network network = ShadowNetwork.newInstance(id);
            state.onDefaultNetworkAvailable(network);
            state.onDefaultNetworkCapabilitiesChanged(network, capabilities(NetworkCapabilities.TRANSPORT_WIFI));
            state.onDefaultNetworkLost(network);
        }
        Field entries = PhysicalNetworkState.class.getDeclaredField("entries");
        entries.setAccessible(true);
        assertTrue(((Map<?, ?>) entries.get(state)).isEmpty());
        assertNull(state.onPhysicalLost(WIFI));
    }

    private static void setField(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private static LinkAddress linkAddress(String address) throws Exception {
        // LinkAddress's value constructor is hidden in the public SDK stub.
        String[] parts = address.split("/");
        return LinkAddress.class.getDeclaredConstructor(InetAddress.class, int.class)
                .newInstance(InetAddress.getByName(parts[0]), Integer.parseInt(parts[1]));
    }
}
