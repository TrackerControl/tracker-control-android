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

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Static pre-computed VPN routes covering all public IPv4 address space,
 * excluding private, reserved, and carrier Wi-Fi calling ranges.
 *
 * Routes are computed once on first access from a fixed exclusion list,
 * avoiding per-rebuild dynamic computation overhead.
 */
public class VpnRoutes {
    private static final String TAG = "TrackerControl.VpnRoutes";
    private static volatile List<IPUtil.CIDR> cachedRoutes;

    // Ranges excluded from VPN routing (must not overlap)
    private static final String[][] EXCLUDED_RANGES = {
            // Reserved and private ranges
            {"0.0.0.0", "8"},       // Current network (RFC 1122)
            {"10.0.0.0", "8"},      // Private (RFC 1918)
            {"100.64.0.0", "10"},   // CGNAT (RFC 6598)
            {"127.0.0.0", "8"},     // Loopback (RFC 1122)
            {"169.254.0.0", "16"},  // Link-local (RFC 3927)
            {"172.16.0.0", "12"},   // Private (RFC 1918)
            {"192.168.0.0", "16"},  // Private (RFC 1918)
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

    /**
     * Returns the list of CIDR routes to add to the VPN builder.
     * Computed once and cached for all subsequent calls.
     */
    public static List<IPUtil.CIDR> getRoutes() {
        if (cachedRoutes == null) {
            synchronized (VpnRoutes.class) {
                if (cachedRoutes == null) {
                    cachedRoutes = computeRoutes();
                }
            }
        }
        return cachedRoutes;
    }

    private static List<IPUtil.CIDR> computeRoutes() {
        // Build sorted exclusion list
        List<IPUtil.CIDR> excludes = new ArrayList<>();
        for (String[] range : EXCLUDED_RANGES) {
            excludes.add(new IPUtil.CIDR(range[0], Integer.parseInt(range[1])));
        }
        Collections.sort(excludes);

        // Compute complement: all IP ranges NOT in the exclusion list
        List<IPUtil.CIDR> routes = new ArrayList<>();
        try {
            long startLong = 0; // 0.0.0.0
            for (IPUtil.CIDR exclude : excludes) {
                long excludeStartLong = inetToLong(exclude.getStart());
                if (excludeStartLong > startLong) {
                    InetAddress gapStart = longToInet(startLong);
                    InetAddress gapEnd = IPUtil.minus1(exclude.getStart());
                    routes.addAll(IPUtil.toCIDR(gapStart, gapEnd));
                }
                long excludeEndLong = inetToLong(exclude.getEnd());
                if (excludeEndLong < 0xFFFFFFFFL) {
                    startLong = excludeEndLong + 1;
                } else {
                    startLong = 0xFFFFFFFFL + 1; // Past end of IPv4 space
                    break;
                }
            }
            // Any remaining space after last exclusion (none if 224.0.0.0/3 is last)
            if (startLong <= 0xFFFFFFFFL) {
                routes.addAll(IPUtil.toCIDR(longToInet(startLong), longToInet(0xFFFFFFFFL)));
            }
        } catch (UnknownHostException ex) {
            Log.e(TAG, "Failed to compute routes: " + ex);
        }

        Log.i(TAG, "Computed " + routes.size() + " VPN routes from "
                + EXCLUDED_RANGES.length + " exclusions");
        return Collections.unmodifiableList(routes);
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
