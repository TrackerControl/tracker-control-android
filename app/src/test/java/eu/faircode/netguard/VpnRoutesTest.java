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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

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

    // --- default (WireGuard off) --------------------------------------------

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
