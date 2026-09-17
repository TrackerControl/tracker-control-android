package eu.faircode.netguard;

import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Callback-owned snapshots: never query ConnectivityManager from its callbacks. */
final class PhysicalNetworkState {
    private static final class Entry {
        List<Integer> transports;
        Boolean metered;
        List<String> routes;
        List<String> dns;
        String privateDns;
        boolean privateDnsActive;
    }

    private final Map<Network, Entry> entries = new HashMap<>();
    private Network defaultNetwork;
    private boolean defaultIsVpn;
    private List<Integer> vpnTransports = Collections.emptyList();
    private Network egress;

    synchronized String onPhysicalAvailable(Network network) {
        if (network != null && !entries.containsKey(network)) entries.put(network, new Entry());
        return null; // Availability alone says nothing about the selected egress.
    }

    synchronized String onPhysicalCapabilitiesChanged(Network network, NetworkCapabilities caps) {
        Entry entry = entries.get(network);
        if (entry == null || caps == null ||
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return null;
        List<Integer> previous = entry.transports;
        Boolean previousMetered = entry.metered;
        entry.transports = transports(caps);
        entry.metered = isMetered(caps);
        String change = selectEgress();
        if (change != null || !network.equals(egress)) return change;
        if (previous != null && !previous.equals(entry.transports))
            return NetworkReloadPolicy.REASON_NETWORK_CHANGED;
        return previousMetered != null && !previousMetered.equals(entry.metered)
                ? NetworkReloadPolicy.REASON_METERED_CHANGED : null;
    }

    synchronized String onPhysicalLinkPropertiesChanged(Network network, LinkProperties props) {
        Entry entry = entries.get(network);
        if (entry == null || props == null) return null;
        List<String> routes = new ArrayList<>();
        for (Object address : props.getLinkAddresses()) routes.add("address:" + address);
        for (Object route : props.getRoutes()) routes.add("route:" + route);
        Collections.sort(routes);
        List<String> dns = new ArrayList<>();
        for (java.net.InetAddress server : props.getDnsServers()) dns.add(server.getHostAddress());
        dns.add("domains:" + props.getDomains());
        Collections.sort(dns);
        String privateDns = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? props.getPrivateDnsServerName() : null;
        boolean active = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && props.isPrivateDnsActive();
        boolean routeChanged = entry.routes != null && !entry.routes.equals(routes);
        boolean dnsChanged = entry.dns != null && !entry.dns.equals(dns);
        boolean privateChanged = !Objects.equals(privateDns, entry.privateDns) ||
                active != entry.privateDnsActive;
        entry.routes = routes;
        entry.dns = dns;
        entry.privateDns = privateDns;
        entry.privateDnsActive = active;
        if (!network.equals(egress)) return null;
        if (routeChanged) return NetworkReloadPolicy.REASON_LINK_PROPERTIES_CHANGED;
        if (dnsChanged) return NetworkReloadPolicy.REASON_DNS_CHANGED;
        return privateChanged ? NetworkReloadPolicy.REASON_PRIVATE_DNS_CHANGED : null;
    }

    synchronized String onPhysicalLost(Network network) {
        if (entries.remove(network) == null) return null;
        return selectEgress();
    }

    synchronized String onDefaultNetworkAvailable(Network network) {
        // onCapabilitiesChanged identifies physical versus VPN. Guessing here
        // would treat our own replacement VPN as a physical handover.
        return null;
    }

    synchronized String onDefaultNetworkCapabilitiesChanged(Network network, NetworkCapabilities caps) {
        if (network == null || caps == null) return null;
        boolean vpn = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN);
        List<Integer> snapshot = transports(caps);
        if (vpn && snapshot.isEmpty()) return null; // Replacement VPN has not inherited transports yet.
        boolean transportChanged = vpn && defaultIsVpn && !vpnTransports.equals(snapshot);
        defaultNetwork = network;
        defaultIsVpn = vpn;
        vpnTransports = vpn ? snapshot : Collections.emptyList();
        // Never create physical entries from this unfiltered callback: only
        // the physical registration guarantees a matching onLost later.
        String change = vpn ? selectEgress() : onPhysicalCapabilitiesChanged(network, caps);
        if (change == null) change = selectEgress();
        return change != null ? change : transportChanged ? NetworkReloadPolicy.REASON_NETWORK_CHANGED : null;
    }

    synchronized String onDefaultNetworkLinkPropertiesChanged(Network network, LinkProperties props) {
        return network != null && network.equals(defaultNetwork) && !defaultIsVpn
                ? onPhysicalLinkPropertiesChanged(network, props) : null;
    }

    synchronized String onDefaultNetworkLost(Network network) {
        if (!Objects.equals(network, defaultNetwork) || defaultIsVpn) return null;
        defaultNetwork = null;
        return selectEgress();
    }

    private String selectEgress() {
        Network selected = null;
        if (!defaultIsVpn) {
            Entry entry = entries.get(defaultNetwork);
            if (entry != null && entry.transports != null) selected = defaultNetwork;
        } else {
            // VPN capabilities expose physical transports, not necessarily an
            // underlying Network identity. Retain a still-matching selection;
            // otherwise select only an unambiguous candidate, never a standby
            // merely because its validation/metered state changed.
            Entry current = entries.get(egress);
            if (current != null && current.transports != null &&
                    !Collections.disjoint(current.transports, vpnTransports)) selected = egress;
            else for (Map.Entry<Network, Entry> candidate : entries.entrySet()) {
                List<Integer> transports = candidate.getValue().transports;
                if (transports == null || Collections.disjoint(transports, vpnTransports)) continue;
                if (selected != null) { selected = null; break; }
                selected = candidate.getKey();
            }
        }
        boolean changed = !Objects.equals(egress, selected);
        egress = selected;
        return changed ? NetworkReloadPolicy.REASON_NETWORK_CHANGED : null;
    }

    @android.annotation.SuppressLint("InlinedApi")
    private static boolean isMetered(NetworkCapabilities caps) {
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED);
    }

    private static List<Integer> transports(NetworkCapabilities caps) {
        List<Integer> result = new ArrayList<>();
        for (int transport = 0; transport < 32; transport++)
            if (transport != NetworkCapabilities.TRANSPORT_VPN && caps.hasTransport(transport)) result.add(transport);
        return result;
    }

    synchronized Network getDefaultNetwork() { return egress; }

    synchronized void reset() {
        entries.clear();
        defaultNetwork = null;
        defaultIsVpn = false;
        vpnTransports = Collections.emptyList();
        egress = null;
    }
}
