package eu.faircode.netguard;

import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Callback-owned snapshots: never query ConnectivityManager from its callbacks. */
final class PhysicalNetworkState {
    private static final class Entry {
        List<?> capabilities;
        List<String> links;
        String privateDns;
        boolean privateDnsActive;
    }

    private final Map<Network, Entry> entries = new HashMap<>();
    private Network defaultNetwork;
    private boolean defaultSeen;
    private List<Integer> vpnTransports;

    synchronized String onPhysicalAvailable(Network network) {
        if (network == null || entries.containsKey(network)) return null;
        entries.put(network, new Entry());
        return NetworkReloadPolicy.REASON_NETWORK_AVAILABLE;
    }

    synchronized String onPhysicalCapabilitiesChanged(Network network, NetworkCapabilities caps) {
        if (network == null || caps == null ||
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return null;
        Entry entry = entry(network);
        List<?> snapshot = capabilities(caps);
        boolean changed = !snapshot.equals(entry.capabilities);
        entry.capabilities = snapshot;
        return changed ? NetworkReloadPolicy.REASON_NETWORK_CHANGED : null;
    }

    synchronized String onPhysicalLinkPropertiesChanged(Network network, LinkProperties props) {
        if (network == null || props == null) return null;
        Entry entry = entry(network);
        List<String> snapshot = links(props);
        String privateDns = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? props.getPrivateDnsServerName() : null;
        boolean active = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && props.isPrivateDnsActive();
        boolean changed = !snapshot.equals(entry.links);
        boolean privateChanged = !Objects.equals(privateDns, entry.privateDns) ||
                active != entry.privateDnsActive;
        entry.links = snapshot;
        entry.privateDns = privateDns;
        entry.privateDnsActive = active;
        if (changed) return NetworkReloadPolicy.REASON_LINK_PROPERTIES_CHANGED;
        return privateChanged ? NetworkReloadPolicy.REASON_PRIVATE_DNS_CHANGED : null;
    }

    synchronized String onPhysicalLost(Network network) {
        if (network == null || entries.remove(network) == null) return null;
        if (network.equals(defaultNetwork)) defaultNetwork = null;
        return NetworkReloadPolicy.REASON_NETWORK_LOST;
    }

    synchronized String onDefaultNetworkAvailable(Network network) {
        return acceptDefaultIfPhysical(network);
    }

    synchronized String onDefaultNetworkCapabilitiesChanged(Network network, NetworkCapabilities caps) {
        if (network == null || caps == null) return null;
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
            // A VPN can stay default while its underlying Wi-Fi/mobile transport
            // changes. Ignore VPN identity churn caused by our own reloads.
            List<Integer> snapshot = transports(caps);
            if (snapshot.isEmpty()) return null;
            boolean changed = vpnTransports != null && !vpnTransports.equals(snapshot);
            vpnTransports = snapshot;
            return changed ? NetworkReloadPolicy.REASON_NETWORK_CHANGED : null;
        }
        String change = onPhysicalCapabilitiesChanged(network, caps);
        String defaultChange = acceptDefaultIfPhysical(network);
        return defaultChange != null ? defaultChange : change;
    }

    synchronized String onDefaultNetworkLinkPropertiesChanged(Network network, LinkProperties props) {
        Entry entry = entries.get(network);
        if (entry == null || entry.capabilities == null) return null;
        String change = onPhysicalLinkPropertiesChanged(network, props);
        String defaultChange = acceptDefaultIfPhysical(network);
        return defaultChange != null ? defaultChange : change;
    }

    synchronized String onDefaultNetworkLost(Network network) {
        if (network != null && network.equals(defaultNetwork)) defaultNetwork = null;
        return null; // The physical callback reports actual loss; VPN loss is self-generated.
    }

    private String acceptDefaultIfPhysical(Network network) {
        Entry entry = entries.get(network);
        if (entry == null || entry.capabilities == null) return null;
        boolean changed = defaultSeen && !network.equals(defaultNetwork);
        defaultNetwork = network;
        defaultSeen = true;
        return changed ? NetworkReloadPolicy.REASON_NETWORK_CHANGED : null;
    }

    private Entry entry(Network network) {
        Entry entry = entries.get(network);
        if (entry == null) {
            entry = new Entry();
            entries.put(network, entry);
        }
        return entry;
    }

    // Compare only route-relevant values, not signal strength or bandwidth.
    // Unknown capability integers safely return false on older Android versions.
    @android.annotation.SuppressLint("InlinedApi")
    private static List<?> capabilities(NetworkCapabilities caps) {
        return Arrays.asList(transports(caps),
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED),
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED));
    }

    private static List<Integer> transports(NetworkCapabilities caps) {
        List<Integer> result = new ArrayList<>();
        for (int transport = 0; transport < 32; transport++)
            if (transport != NetworkCapabilities.TRANSPORT_VPN && caps.hasTransport(transport))
                result.add(transport);
        return result;
    }

    private static List<String> links(LinkProperties props) {
        // Retain immutable values rather than mutable callback objects. This
        // also avoids LinkProperties constructors/setters that require API 29.
        List<String> result = new ArrayList<>();
        for (Object address : props.getLinkAddresses()) result.add("address:" + address);
        for (Object route : props.getRoutes()) result.add("route:" + route);
        for (java.net.InetAddress dns : props.getDnsServers()) result.add("dns:" + dns.getHostAddress());
        result.add("domains:" + props.getDomains());
        Collections.sort(result);
        return result;
    }

    synchronized Network getDefaultNetwork() {
        return defaultNetwork;
    }

    synchronized void reset() {
        entries.clear();
        defaultNetwork = null;
        defaultSeen = false;
        vpnTransports = null;
    }
}
