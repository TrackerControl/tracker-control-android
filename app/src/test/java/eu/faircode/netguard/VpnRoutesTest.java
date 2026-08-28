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
 */

package eu.faircode.netguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Verifies the RFC 1918 / WireGuard AllowedIPs route-computation logic in
 * {@link VpnRoutes} (issue #593).
 */
@RunWith(RobolectricTestRunner.class)
public class VpnRoutesTest {

    // --- helpers -------------------------------------------------------------

    private static long toLong(java.net.InetAddress addr) {
        long r = 0;
        for (byte b : addr.getAddress())
            r = r << 8 | (b & 0xFF);
        return r;
    }

    /** True if the given IPv4 address is inside any route in the list. */
    private static boolean isRouted(List<IPUtil.CIDR> routes, String ip) throws Exception {
        long target = toLong(java.net.InetAddress.getByName(ip));
        for (IPUtil.CIDR route : routes) {
            long start = toLong(route.getStart());
            long end = toLong(route.getEnd());
            if (target >= start && target <= end)
                return true;
        }
        return false;
    }

    private static long[] interval(String[] range) throws Exception {
        int prefix = Integer.parseInt(range[1]);
        long address = toLong(java.net.InetAddress.getByName(range[0]));
        int hostBits = 32 - prefix;
        long mask = hostBits == 32 ? 0 : 0xFFFFFFFFL << hostBits;
        long start = address & mask;
        return new long[]{start, start + (1L << hostBits) - 1};
    }

    private static String[][] privateRanges(String fieldName) throws Exception {
        Field field = VpnRoutes.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (String[][]) field.get(null);
    }

    // --- default (WireGuard off) --------------------------------------------

    @Test
    public void alwaysExcludedRangesAreAlignedAndDoNotOverlapPrivateRanges() throws Exception {
        String[][] alwaysExcluded = privateRanges("ALWAYS_EXCLUDED");
        String[][] rfc1918Ranges = privateRanges("RFC1918_RANGES");

        for (String[] range : alwaysExcluded) {
            long[] actual = interval(range);
            int hostBits = 32 - Integer.parseInt(range[1]);
            long mask = hostBits == 32 ? 0 : 0xFFFFFFFFL << hostBits;
            long address = toLong(java.net.InetAddress.getByName(range[0]));
            assertEquals(range[0] + "/" + range[1], address & mask, address);
        }

        for (int i = 0; i < alwaysExcluded.length; i++) {
            long[] left = interval(alwaysExcluded[i]);
            for (int j = i + 1; j < alwaysExcluded.length; j++) {
                long[] right = interval(alwaysExcluded[j]);
                assertTrue(left[1] < right[0] || right[1] < left[0]);
            }
            for (String[] range : rfc1918Ranges) {
                long[] right = interval(range);
                assertTrue(left[1] < right[0] || right[1] < left[0]);
            }
        }
    }

    @Test
    public void defaultRoutesExcludeAllRfc1918AndReserved() throws Exception {
        List<IPUtil.CIDR> routes = VpnRoutes.getRoutes();

        // Public space is routed.
        assertTrue(isRouted(routes, "8.8.8.8"));
        assertTrue(isRouted(routes, "1.1.1.1"));

        // RFC 1918 is excluded (bypasses tunnel, reaches LAN directly).
        assertFalse(isRouted(routes, "10.0.0.1"));
        assertFalse(isRouted(routes, "172.16.5.5"));
        assertFalse(isRouted(routes, "192.168.1.10"));

        // Reserved / loopback / link-local / multicast excluded.
        assertFalse(isRouted(routes, "127.0.0.1"));
        assertFalse(isRouted(routes, "169.254.1.1"));
        assertFalse(isRouted(routes, "224.0.0.1"));
        assertFalse(isRouted(routes, "100.64.0.1"));
    }

    @Test
    public void emptyOrIpv6OnlyAllowedIpsFallsBackToDefault() throws Exception {
        List<IPUtil.CIDR> viaEmpty = VpnRoutes.getRoutes(Collections.emptyList());
        List<IPUtil.CIDR> viaV6 = VpnRoutes.getRoutes(Collections.singletonList("::/0"));

        assertFalse(isRouted(viaEmpty, "192.168.1.10"));
        assertFalse(isRouted(viaV6, "192.168.1.10"));
    }

    // --- tethering compatibility mode ---------------------------------------

    @Test
    public void tetheringRoutesAreASingleDefaultRoute() throws Exception {
        List<IPUtil.CIDR> routes = VpnRoutes.getTetheringRoutes();

        assertEquals(1, routes.size());
        assertEquals(0, routes.get(0).prefix);

        // Everything is inside the tunnel, including the ranges the default
        // route set excludes: the tethering downstream subnets and the DHCP
        // broadcast the tethered client uses to get a lease (#699).
        assertTrue(isRouted(routes, "8.8.8.8"));
        assertTrue(isRouted(routes, "192.168.42.129")); // USB tethering
        assertTrue(isRouted(routes, "192.168.43.1"));   // Wi-Fi tethering
        assertTrue(isRouted(routes, "192.168.44.1"));   // Bluetooth tethering
        assertTrue(isRouted(routes, "10.0.0.1"));
        assertTrue(isRouted(routes, "100.64.0.1"));
        assertTrue(isRouted(routes, "0.0.0.0"));
        assertTrue(isRouted(routes, "255.255.255.255")); // DHCP broadcast
    }

    @Test
    public void tetheringRoutesDoNotAffectTheDefaultRouteSet() throws Exception {
        VpnRoutes.getTetheringRoutes();
        List<IPUtil.CIDR> routes = VpnRoutes.getRoutes();

        assertFalse(isRouted(routes, "192.168.42.129"));
        assertTrue(isRouted(routes, "8.8.8.8"));
    }

    // --- WireGuard active: AllowedIPs authoritative --------------------------

    @Test
    public void allowedIpsZeroSlashZeroRoutesAllRfc1918IntoTunnel() throws Exception {
        List<IPUtil.CIDR> routes = VpnRoutes.getRoutes(Collections.singletonList("0.0.0.0/0"));

        // All RFC 1918 now enters the tunnel.
        assertTrue(isRouted(routes, "10.0.0.1"));
        assertTrue(isRouted(routes, "172.16.5.5"));
        assertTrue(isRouted(routes, "192.168.1.10"));
        assertTrue(isRouted(routes, "8.8.8.8"));

        // Loopback / link-local / multicast / CGNAT stay excluded even with /0.
        assertFalse(isRouted(routes, "127.0.0.1"));
        assertFalse(isRouted(routes, "169.254.1.1"));
        assertFalse(isRouted(routes, "224.0.0.1"));
        assertFalse(isRouted(routes, "100.64.0.1"));
        assertFalse(isRouted(routes, "0.0.0.5"));
    }

    @Test
    public void explicitLanSubnetRoutesOnlyThatSubnet() throws Exception {
        List<IPUtil.CIDR> routes = VpnRoutes.getRoutes(Collections.singletonList("192.168.1.0/24"));

        // The covered subnet enters the tunnel.
        assertTrue(isRouted(routes, "192.168.1.10"));
        assertTrue(isRouted(routes, "192.168.1.254"));

        // The rest of 192.168/16 and other RFC 1918 ranges stay excluded.
        assertFalse(isRouted(routes, "192.168.2.10"));
        assertFalse(isRouted(routes, "10.0.0.1"));
        assertFalse(isRouted(routes, "172.16.5.5"));
    }

    @Test
    public void multipleAllowedIpsAreUnioned() throws Exception {
        List<IPUtil.CIDR> routes = VpnRoutes.getRoutes(
                Arrays.asList("10.10.0.0/16", "192.168.50.0/24"));

        assertTrue(isRouted(routes, "10.10.0.1"));
        assertTrue(isRouted(routes, "192.168.50.5"));

        assertFalse(isRouted(routes, "10.20.0.1"));   // outside 10.10/16
        assertFalse(isRouted(routes, "192.168.51.5")); // outside 192.168.50/24
        assertFalse(isRouted(routes, "172.16.5.5"));
    }

    @Test
    public void allowedIpsNeverReExposeReservedRanges() throws Exception {
        // A profile that lists loopback/link-local must not route them.
        List<IPUtil.CIDR> routes = VpnRoutes.getRoutes(
                Arrays.asList("127.0.0.0/8", "169.254.0.0/16", "192.168.0.0/16"));

        assertFalse(isRouted(routes, "127.0.0.1"));
        assertFalse(isRouted(routes, "169.254.1.1"));
        assertTrue(isRouted(routes, "192.168.1.10"));
    }
}
