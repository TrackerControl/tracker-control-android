/*
 * This file is part of TrackerControl.
 *
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * TrackerControl is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with TrackerControl.  If not, see <http://www.gnu.org/licenses/>.
 *
 * VPN routing approach informed by DuckDuckGo App Tracking Protection (Apache 2.0).
 * See: https://github.com/duckduckgo/Android
 */

package eu.faircode.netguard;

import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * VPN routes covering all public IPv4 address space, excluding private,
 * reserved, and carrier Wi-Fi calling ranges.
 *
 * <p>The default route set (WireGuard remote egress off) is computed once and
 * cached. RFC 1918 private ranges (10/8, 172.16/12, 192.168/16) are normally
 * excluded so LAN traffic bypasses the tunnel and reaches the local network
 * directly.
 *
 * <p>When WireGuard remote egress is active, the profile's {@code AllowedIPs}
 * become authoritative: any part of an RFC 1918 range that a peer's
 * {@code AllowedIPs} covers (e.g. {@code 0.0.0.0/0} or an explicit
 * {@code 192.168.1.0/24}) is routed into the tunnel instead of being excluded,
 * so split-DNS setups that resolve self-hosted services to private addresses
 * behind the WireGuard endpoint become reachable (issue #593). Loopback,
 * link-local, multicast, CGNAT, current-network, and carrier Wi-Fi-calling
 * ranges are ALWAYS excluded regardless of AllowedIPs.
 */
public class VpnRoutes {
    private static final String TAG = "TrackerControl.VpnRoutes";
    private static volatile List<IPUtil.CIDR> cachedRoutes;

    // Single-entry cache for the WireGuard-active case: the config rarely
    // changes but getBuilder() runs on every VPN rebuild, so caching by the
    // normalized AllowedIPs signature avoids recomputing the complement.
    private static volatile String cachedAllowedKey;
    private static volatile List<IPUtil.CIDR> cachedAllowedRoutes;

    // Ranges ALWAYS excluded from VPN routing, regardless of WireGuard
    // AllowedIPs (must not overlap each other or RFC1918_RANGES).
    private static final String[][] ALWAYS_EXCLUDED = {
            // Reserved ranges
            {"0.0.0.0", "8"},       // Current network (RFC 1122)
            {"100.64.0.0", "10"},   // CGNAT (RFC 6598)
            {"127.0.0.0", "8"},     // Loopback (RFC 1122)
            {"169.254.0.0", "16"},  // Link-local (RFC 3927)
            {"224.0.0.0", "3"},     // Multicast (224/4) + reserved (240/4)

            // T-Mobile Wi-Fi calling
            {"66.94.2.0", "24"},
            {"66.94.6.0", "23"},
            {"66.94.8.0", "22"},
            {"208.54.0.0", "16"},

            // Verizon Wi-Fi calling
            {"66.82.0.0", "15"},
            {"66.174.0.0", "16"},
            {"69.96.0.0", "13"},
            {"70.192.0.0", "11"},
            {"72.96.0.0", "9"},
            {"75.192.0.0", "9"},
            {"97.0.0.0", "10"},
            {"97.128.0.0", "9"},
            {"174.192.0.0", "9"},
    };

    // RFC 1918 private ranges. Excluded by default so LAN traffic bypasses the
    // tunnel, but included (routed into the tunnel) for the portion covered by
    // the active WireGuard profile's AllowedIPs.
    private static final String[][] RFC1918_RANGES = {
            {"10.0.0.0", "8"},      // Private (RFC 1918)
            {"172.16.0.0", "12"},   // Private (RFC 1918)
            {"192.168.0.0", "16"},  // Private (RFC 1918)
    };

    /**
     * Returns the default route list (WireGuard remote egress off): all public
     * IPv4 space excluding private, reserved, and carrier Wi-Fi calling ranges.
     * Computed once and cached for all subsequent calls.
     */
    public static List<IPUtil.CIDR> getRoutes() {
        if (cachedRoutes == null) {
            synchronized (VpnRoutes.class) {
                if (cachedRoutes == null) {
                    cachedRoutes = computeRoutes(Collections.emptyList());
                }
            }
        }
        return cachedRoutes;
    }

    /**
     * Returns the route list for an active WireGuard remote-egress tunnel,
     * making the profile's AllowedIPs authoritative over the RFC 1918
     * exclusions. Any part of an RFC 1918 range covered by {@code wgAllowedIps}
     * is routed into the tunnel; everything else behaves exactly as
     * {@link #getRoutes()}.
     *
     * @param wgAllowedIps the union of every peer's {@code AllowedIPs} entries
     *                     (CIDR strings; IPv6 entries are ignored for now).
     */
    public static List<IPUtil.CIDR> getRoutes(List<String> wgAllowedIps) {
        List<IPUtil.CIDR> allowedV4 = parseV4AllowedIps(wgAllowedIps);
        if (allowedV4.isEmpty())
            return getRoutes(); // No IPv4 AllowedIPs — identical to WG-off default

        String key = allowedKey(allowedV4);
        List<IPUtil.CIDR> cached = cachedAllowedRoutes;
        if (cached != null && key.equals(cachedAllowedKey))
            return cached;

        synchronized (VpnRoutes.class) {
            if (cachedAllowedRoutes != null && key.equals(cachedAllowedKey))
                return cachedAllowedRoutes;
            List<IPUtil.CIDR> routes = computeRoutes(allowedV4);
            cachedAllowedRoutes = routes;
            cachedAllowedKey = key;
            return routes;
        }
    }

    private static List<IPUtil.CIDR> computeRoutes(List<IPUtil.CIDR> allowedV4) {
        // Build the excluded intervals: always-excluded ranges verbatim, plus
        // each RFC 1918 range with any AllowedIPs-covered portion carved out.
        List<long[]> excluded = new ArrayList<>();
        for (String[] range : ALWAYS_EXCLUDED)
            excluded.add(toInterval(range[0], Integer.parseInt(range[1])));

        List<long[]> allowed = new ArrayList<>();
        for (IPUtil.CIDR cidr : allowedV4)
            allowed.add(new long[]{inetToLong(cidr.getStart()), inetToLong(cidr.getEnd())});

        for (String[] range : RFC1918_RANGES) {
            long[] interval = toInterval(range[0], Integer.parseInt(range[1]));
            excluded.addAll(subtract(interval[0], interval[1], allowed));
        }

        excluded.sort((a, b) -> Long.compare(a[0], b[0]));

        // Compute the complement: all IPv4 space NOT in the exclusion list.
        List<IPUtil.CIDR> routes = new ArrayList<>();
        try {
            long startLong = 0; // 0.0.0.0
            for (long[] exclude : excluded) {
                if (exclude[0] > startLong)
                    routes.addAll(IPUtil.toCIDR(longToInet(startLong), longToInet(exclude[0] - 1)));
                if (exclude[1] >= 0xFFFFFFFFL) {
                    startLong = 0xFFFFFFFFL + 1; // Past end of IPv4 space
                    break;
                }
                if (exclude[1] + 1 > startLong)
                    startLong = exclude[1] + 1;
            }
            // Any remaining space after the last exclusion.
            if (startLong <= 0xFFFFFFFFL)
                routes.addAll(IPUtil.toCIDR(longToInet(startLong), longToInet(0xFFFFFFFFL)));
        } catch (UnknownHostException ex) {
            Log.e(TAG, "Failed to compute routes: " + ex);
        }

        Log.i(TAG, "Computed " + routes.size() + " VPN routes from "
                + excluded.size() + " exclusions (" + allowedV4.size() + " WG AllowedIPs)");
        return Collections.unmodifiableList(routes);
    }

    /**
     * Returns the sub-intervals of [start, end] not covered by any interval in
     * {@code allowed}. {@code allowed} intervals may overlap and be unsorted.
     */
    private static List<long[]> subtract(long start, long end, List<long[]> allowed) {
        // Clip the allowed intervals to [start, end] and sort by start.
        List<long[]> overlaps = new ArrayList<>();
        for (long[] a : allowed) {
            long s = Math.max(a[0], start);
            long e = Math.min(a[1], end);
            if (s <= e)
                overlaps.add(new long[]{s, e});
        }
        overlaps.sort((a, b) -> Long.compare(a[0], b[0]));

        List<long[]> result = new ArrayList<>();
        long cursor = start;
        for (long[] a : overlaps) {
            if (a[0] > cursor)
                result.add(new long[]{cursor, a[0] - 1});
            if (a[1] + 1 > cursor)
                cursor = a[1] + 1;
            if (cursor > end)
                break;
        }
        if (cursor <= end)
            result.add(new long[]{cursor, end});
        return result;
    }

    /** Parse WireGuard AllowedIPs CIDR strings, keeping only valid IPv4 entries. */
    private static List<IPUtil.CIDR> parseV4AllowedIps(List<String> wgAllowedIps) {
        List<IPUtil.CIDR> allowedV4 = new ArrayList<>();
        if (wgAllowedIps == null)
            return allowedV4;
        for (String raw : wgAllowedIps) {
            if (raw == null)
                continue;
            String entry = raw.trim();
            if (entry.isEmpty())
                continue;
            try {
                String[] parts = entry.split("/");
                String ip = parts[0].trim();
                if (ip.contains(":"))
                    continue; // IPv6 — see class docs; tracked as follow-up
                int prefix = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 32;
                if (prefix < 0 || prefix > 32)
                    continue;
                InetAddress addr = InetAddress.getByName(ip);
                if (!(addr instanceof Inet4Address))
                    continue;
                allowedV4.add(new IPUtil.CIDR(ip, prefix));
            } catch (Throwable ex) {
                Log.w(TAG, "Ignoring malformed AllowedIPs entry '" + entry + "': " + ex);
            }
        }
        return allowedV4;
    }

    /** Stable signature of an AllowedIPs set for single-entry cache keying. */
    private static String allowedKey(List<IPUtil.CIDR> allowedV4) {
        List<String> keys = new ArrayList<>(allowedV4.size());
        for (IPUtil.CIDR cidr : allowedV4)
            keys.add(inetToLong(cidr.getStart()) + "/" + cidr.prefix);
        Collections.sort(keys);
        return String.join(",", keys);
    }

    private static long[] toInterval(String ip, int prefix) {
        IPUtil.CIDR cidr = new IPUtil.CIDR(ip, prefix);
        return new long[]{inetToLong(cidr.getStart()), inetToLong(cidr.getEnd())};
    }

    private static long inetToLong(InetAddress addr) {
        long result = 0;
        for (byte b : addr.getAddress())
            result = result << 8 | (b & 0xFF);
        return result;
    }

    private static InetAddress longToInet(long addr) {
        try {
            byte[] b = new byte[4];
            for (int i = 3; i >= 0; i--) {
                b[i] = (byte) (addr & 0xFF);
                addr >>= 8;
            }
            return InetAddress.getByAddress(b);
        } catch (UnknownHostException ignore) {
            return null;
        }
    }
}
