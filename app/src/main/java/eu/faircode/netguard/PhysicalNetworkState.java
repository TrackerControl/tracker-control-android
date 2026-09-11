package eu.faircode.netguard;

import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Parcel;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Callback-owned state for physical networks.  Connectivity callbacks can be
 * reordered and do not make synchronous ConnectivityManager lookups safe, so
 * the callback snapshots are the source of truth until the next callback.
 */
final class PhysicalNetworkState {
    static final class Change {
        private final String reason;
        private final boolean privateDnsChanged;

        private Change(String reason, boolean privateDnsChanged) {
            this.reason = reason;
            this.privateDnsChanged = privateDnsChanged;
        }

        static Change none() {
            return new Change(null, false);
        }

        static Change of(String reason, boolean privateDnsChanged) {
            return new Change(reason, privateDnsChanged);
        }

        String getReason() {
            return reason;
        }

        boolean isPrivateDnsChanged() {
            return privateDnsChanged;
        }
    }

    private static final class Entry {
        NetworkCapabilities capabilities;
        LinkProperties linkProperties;
        Fingerprint fingerprint;
        String privateDns;
        boolean privateDnsActive;

        Entry(NetworkCapabilities capabilities, LinkProperties linkProperties) {
            updateCapabilities(capabilities);
            updateLinkProperties(linkProperties);
        }

        void updateCapabilities(NetworkCapabilities supplied) {
            capabilities = supplied == null ? null : new NetworkCapabilities(supplied);
            fingerprint = Fingerprint.from(capabilities, linkProperties);
        }

        boolean updateLinkProperties(LinkProperties supplied) {
            String oldPrivateDns = privateDns;
            boolean oldPrivateDnsActive = privateDnsActive;
            // Parcelable copying works on older Android releases too; public
            // LinkProperties constructors and setters require API 29.
            String newPrivateDns = null;
            boolean newPrivateDnsActive = false;
            if (supplied != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                newPrivateDns = supplied.getPrivateDnsServerName();
                newPrivateDnsActive = supplied.isPrivateDnsActive();
            }
            linkProperties = copyLinkProperties(supplied);
            privateDns = newPrivateDns;
            privateDnsActive = newPrivateDnsActive;
            fingerprint = Fingerprint.from(capabilities, linkProperties);
            return !Objects.equals(oldPrivateDns, privateDns) ||
                    oldPrivateDnsActive != privateDnsActive;
        }

        private static LinkProperties copyLinkProperties(LinkProperties supplied) {
            if (supplied == null)
                return null;
            Parcel parcel = Parcel.obtain();
            try {
                supplied.writeToParcel(parcel, 0);
                parcel.setDataPosition(0);
                return LinkProperties.CREATOR.createFromParcel(parcel);
            } finally {
                parcel.recycle();
            }
        }
    }

    private static final class Fingerprint {
        // hasCapability safely returns false for capabilities an older OS
        // does not know; these integer constants do not invoke newer APIs.
        @android.annotation.SuppressLint("InlinedApi")
        private static final int[] CAPABILITIES = new int[]{
                NetworkCapabilities.NET_CAPABILITY_INTERNET,
                NetworkCapabilities.NET_CAPABILITY_VALIDATED,
                NetworkCapabilities.NET_CAPABILITY_NOT_VPN,
                NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED,
                NetworkCapabilities.NET_CAPABILITY_NOT_METERED,
                NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED
        };

        private final boolean[] capabilities;
        private final int[] transports;
        private final List<String> linkAddresses;
        private final List<String> routes;
        private final List<String> dnsServers;
        private final String domains;

        private Fingerprint(boolean[] capabilities, int[] transports,
                            List<String> linkAddresses, List<String> routes,
                            List<String> dnsServers, String domains) {
            this.capabilities = capabilities;
            this.transports = transports;
            this.linkAddresses = linkAddresses;
            this.routes = routes;
            this.dnsServers = dnsServers;
            this.domains = domains;
        }

        static Fingerprint from(NetworkCapabilities caps, LinkProperties props) {
            boolean[] capabilities = new boolean[CAPABILITIES.length];
            if (caps != null)
                for (int i = 0; i < CAPABILITIES.length; i++)
                    capabilities[i] = caps.hasCapability(CAPABILITIES[i]);

            List<Integer> transportTypes = new ArrayList<>();
            if (caps != null) {
                // hasTransport is available on the minimum supported API. Do
                // not use getTransportTypes(), which is newer than API 24.
                for (int transport = 0; transport < 32; transport++)
                    if (caps.hasTransport(transport))
                        transportTypes.add(transport);
            }
            int[] transports = new int[transportTypes.size()];
            for (int i = 0; i < transportTypes.size(); i++)
                transports[i] = transportTypes.get(i);
            Arrays.sort(transports);

            List<String> linkAddresses = new ArrayList<>();
            List<String> routes = new ArrayList<>();
            List<String> dnsServers = new ArrayList<>();
            String domains = null;
            if (props != null) {
                for (LinkAddress address : props.getLinkAddresses())
                    linkAddresses.add(address.toString());
                for (Object route : props.getRoutes())
                    routes.add(String.valueOf(route));
                for (InetAddress dns : props.getDnsServers())
                    dnsServers.add(dns.getHostAddress());
                domains = props.getDomains();
            }
            Collections.sort(linkAddresses);
            Collections.sort(routes);
            Collections.sort(dnsServers);
            return new Fingerprint(capabilities, transports, linkAddresses, routes,
                    dnsServers, domains);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Fingerprint))
                return false;
            Fingerprint that = (Fingerprint) other;
            return Arrays.equals(capabilities, that.capabilities) &&
                    Arrays.equals(transports, that.transports) &&
                    Objects.equals(linkAddresses, that.linkAddresses) &&
                    Objects.equals(routes, that.routes) &&
                    Objects.equals(dnsServers, that.dnsServers) &&
                    Objects.equals(domains, that.domains);
        }

        @Override
        public int hashCode() {
            int result = Arrays.hashCode(capabilities);
            result = 31 * result + Arrays.hashCode(transports);
            result = 31 * result + linkAddresses.hashCode();
            result = 31 * result + routes.hashCode();
            result = 31 * result + dnsServers.hashCode();
            result = 31 * result + Objects.hashCode(domains);
            return result;
        }
    }

    private final Map<Network, Entry> entries = new HashMap<>();
    private Network defaultNetwork;
    private boolean baselineEstablished;
    private int[] defaultVpnTransports;

    synchronized Change onPhysicalAvailable(Network network) {
        if (network == null || entries.containsKey(network))
            return Change.none();
        entries.put(network, new Entry(null, null));
        return Change.of(NetworkReloadPolicy.REASON_NETWORK_AVAILABLE, false);
    }

    synchronized Change onPhysicalCapabilitiesChanged(Network network, NetworkCapabilities supplied) {
        if (network == null || supplied == null ||
                !supplied.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
            return Change.none();
        Entry entry = entries.get(network);
        if (entry == null) {
            entry = new Entry(null, null);
            entries.put(network, entry);
        }
        boolean changed = !entry.fingerprint.equals(Fingerprint.from(supplied, entry.linkProperties));
        entry.updateCapabilities(supplied);
        if (changed)
            return Change.of(NetworkReloadPolicy.REASON_NETWORK_CHANGED, false);
        return Change.none();
    }

    synchronized Change onPhysicalLinkPropertiesChanged(Network network, LinkProperties supplied) {
        if (network == null)
            return Change.none();
        Entry entry = entries.get(network);
        if (entry == null) {
            entry = new Entry(null, null);
            entries.put(network, entry);
        }
        Fingerprint oldFingerprint = entry.fingerprint;
        boolean privateDnsChanged = entry.updateLinkProperties(supplied);
        boolean changed = !oldFingerprint.equals(entry.fingerprint);
        if (changed)
            return Change.of(NetworkReloadPolicy.REASON_LINK_PROPERTIES_CHANGED, privateDnsChanged);
        if (privateDnsChanged)
            return Change.of(NetworkReloadPolicy.REASON_PRIVATE_DNS_CHANGED, true);
        return Change.none();
    }

    synchronized Change onPhysicalLost(Network network) {
        if (network == null || entries.remove(network) == null)
            return Change.none();
        if (network.equals(defaultNetwork))
            defaultNetwork = null;
        return Change.of(NetworkReloadPolicy.REASON_NETWORK_LOST, false);
    }

    synchronized Change onDefaultNetworkAvailable(Network network) {
        if (network == null)
            return Change.none();
        return acceptDefaultIfPhysical(network);
    }

    synchronized Change onDefaultNetworkCapabilitiesChanged(Network network, NetworkCapabilities supplied) {
        if (network == null || supplied == null)
            return Change.none();
        if (!supplied.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
            // The VPN often remains the default Network across Wi-Fi/cellular
            // handover. Its physical transport set changes even when both
            // underlying networks were already available. Ignore VPN identity
            // and link-property churn caused by our own establish/reload.
            int[] transports = Fingerprint.from(supplied, null).transports;
            int count = 0;
            for (int transport : transports)
                if (transport != NetworkCapabilities.TRANSPORT_VPN)
                    transports[count++] = transport;
            if (count == 0)
                return Change.none();
            transports = Arrays.copyOf(transports, count);
            boolean changed = defaultVpnTransports != null &&
                    !Arrays.equals(defaultVpnTransports, transports);
            defaultVpnTransports = transports;
            return changed ? Change.of(NetworkReloadPolicy.REASON_NETWORK_CHANGED, false) : Change.none();
        }
        Change change = onPhysicalCapabilitiesChanged(network, supplied);
        Change defaultChange = acceptDefaultIfPhysical(network);
        if (defaultChange.getReason() != null)
            return defaultChange;
        return change;
    }

    synchronized Change onDefaultNetworkLinkPropertiesChanged(Network network, LinkProperties supplied) {
        Entry entry = entries.get(network);
        if (entry == null || entry.capabilities == null ||
                !entry.capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
            return Change.none();
        Change change = onPhysicalLinkPropertiesChanged(network, supplied);
        Change defaultChange = acceptDefaultIfPhysical(network);
        if (defaultChange.getReason() != null)
            return defaultChange;
        return change;
    }

    synchronized Change onDefaultNetworkLost(Network network) {
        if (network == null || !network.equals(defaultNetwork))
            return Change.none();
        defaultNetwork = null;
        return Change.none();
    }

    private Change acceptDefaultIfPhysical(Network network) {
        Entry entry = entries.get(network);
        if (entry == null || entry.capabilities == null ||
                !entry.capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
            return Change.none();
        if (!baselineEstablished) {
            defaultNetwork = network;
            baselineEstablished = true;
            return Change.none();
        }
        if (!network.equals(defaultNetwork)) {
            defaultNetwork = network;
            return Change.of("Network changed", false);
        }
        return Change.none();
    }

    synchronized Network getDefaultNetwork() {
        return defaultNetwork;
    }

    synchronized NetworkCapabilities getCapabilities(Network network) {
        Entry entry = entries.get(network);
        return entry == null || entry.capabilities == null ? null :
                new NetworkCapabilities(entry.capabilities);
    }

    synchronized LinkProperties getLinkProperties(Network network) {
        Entry entry = entries.get(network);
        return entry == null ? null : Entry.copyLinkProperties(entry.linkProperties);
    }

    synchronized void reset() {
        entries.clear();
        defaultNetwork = null;
        baselineEstablished = false;
        defaultVpnTransports = null;
    }
}
