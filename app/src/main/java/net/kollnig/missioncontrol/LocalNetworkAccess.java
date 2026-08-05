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

package net.kollnig.missioncontrol;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import net.kollnig.missioncontrol.wg.WgConfig;
import net.kollnig.missioncontrol.wg.WgConfigParser;
import net.kollnig.missioncontrol.wg.WgPeer;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;

/**
 * Android 17 (API 37) "local network protections": traffic to and from local
 * network addresses requires the {@code ACCESS_LOCAL_NETWORK} runtime
 * permission for apps targeting API 37 or higher. Without it, TCP connections
 * time out and UDP fails with {@code EPERM}.
 *
 * <p>Traffic other apps send to the LAN is unaffected by TrackerControl's
 * permission state: those are their own sockets, and TrackerControl keeps RFC
 * 1918 ranges out of its routes (see {@link eu.faircode.netguard.VpnRoutes}),
 * so that traffic never enters the tun. What does depend on this permission is
 * traffic TrackerControl itself sends to the local network:
 *
 * <ul>
 *   <li>a custom VPN DNS server on the LAN (Pi-hole, AdGuard Home, the router).
 *       Such resolvers get a host route into the tun, so the queries are
 *       re-sent from TrackerControl's own socket (#701);</li>
 *   <li>Secure DNS (DoH) pointed at a local resolver — an ordinary HTTPS
 *       connection, with no DNS exemption to fall back on;</li>
 *   <li>tethering compatibility mode, which installs a full-tunnel default
 *       route, so LAN traffic no longer bypasses the VPN;</li>
 *   <li>a WireGuard peer hosted on the LAN, whose endpoint socket is local;</li>
 *   <li>a SOCKS5 proxy on the LAN, which the native engine dials from our own
 *       socket in the same way.</li>
 * </ul>
 *
 * <p>The system's own resolvers are deliberately not treated as needing the
 * permission: Android exempts port 53 traffic to the network's DNS servers, so
 * the common "router is the DNS server" setup keeps working untouched. Only
 * configuration that points TrackerControl somewhere else on the LAN triggers
 * the prompt, which keeps the permission request off the path of users who
 * never need it.
 */
public class LocalNetworkAccess {
    private static final String TAG = "TrackerControl.LocalNet";

    /**
     * Runtime permission guarding local network access. Referenced by name
     * because {@code Manifest.permission.ACCESS_LOCAL_NETWORK} only exists in
     * API 36+ SDKs, and the manifest declares the same string.
     */
    public static final String PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK";

    /** Android 17. Enforcement applies to apps targeting this level or higher. */
    private static final int SDK_LOCAL_NETWORK_PROTECTION = 37;

    /** Preferences that can point TrackerControl at the local network. */
    private static final String[] SETTINGS = {
            "dns", "dns2",                      // custom VPN DNS servers
            "doh_enabled", "doh_endpoint",      // Secure DNS
            "tcp_mss_clamp",                    // tethering compatibility mode
            "wg_enabled", "wg_config",          // WireGuard remote egress
            "socks5_enabled", "socks5_addr",    // SOCKS5 egress
    };

    /** Whether the running Android version enforces local network protections. */
    public static boolean isEnforced() {
        return Build.VERSION.SDK_INT >= SDK_LOCAL_NETWORK_PROTECTION;
    }

    /** Whether changing {@code name} can change {@link #isMissing(Context)}. */
    public static boolean isRelevantSetting(String name) {
        for (String setting : SETTINGS)
            if (setting.equals(name))
                return true;
        return false;
    }

    public static boolean isGranted(Context context) {
        if (!isEnforced())
            return true;
        return ContextCompat.checkSelfPermission(context, PERMISSION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Whether local network access is both needed by the current configuration
     * and not granted — i.e. whether something the user configured is about to
     * break, or has already broken.
     */
    public static boolean isMissing(Context context) {
        // Cheapest checks first: nothing to do below Android 17, and parsing the
        // WireGuard config is pointless once the permission is granted.
        return isEnforced() && !isGranted(context) && isConfigured(context);
    }

    /** Whether the current configuration makes TrackerControl talk to the LAN. */
    public static boolean isConfigured(Context context) {
        return isConfigured(PreferenceManager.getDefaultSharedPreferences(context));
    }

    public static boolean isConfigured(SharedPreferences prefs) {
        if (isLocalAddress(prefs.getString("dns", null)) ||
                isLocalAddress(prefs.getString("dns2", null)))
            return true;

        if (prefs.getBoolean("doh_enabled", false) &&
                isLocalUrlHost(prefs.getString("doh_endpoint", null)))
            return true;

        if (prefs.getBoolean("tcp_mss_clamp", false))
            return true;

        // The native engine dials the SOCKS5 proxy from our own socket, exactly
        // like the WireGuard endpoint below (see ServiceSinkhole.jni_socks5).
        if (prefs.getBoolean("socks5_enabled", false) &&
                isLocalAddress(prefs.getString("socks5_addr", null)))
            return true;

        return prefs.getBoolean("wg_enabled", false) &&
                hasLocalWireGuardEndpoint(prefs.getString("wg_config", null));
    }

    /**
     * Whether {@code address} is a numeric address on the local network: an RFC
     * 1918 range, the RFC 6598 range some routers use on their LAN side, a
     * link-local address, or an IPv6 unique local address. Only literals are
     * considered — resolving a hostname here would mean a network lookup on the
     * caller's (often main) thread.
     */
    static boolean isLocalAddress(String address) {
        if (TextUtils.isEmpty(address))
            return false;

        InetAddress addr = parseNumeric(address.trim());
        if (addr == null)
            return false;

        if (addr.isLoopbackAddress() || addr.isAnyLocalAddress())
            return false; // The device itself, not the local network
        if (addr.isLinkLocalAddress() || addr.isSiteLocalAddress())
            return true;
        if (addr instanceof Inet6Address) {
            // Unique local addresses (fc00::/7) — isSiteLocalAddress() only
            // covers the deprecated fec0::/10 range.
            byte[] bytes = addr.getAddress();
            return (bytes[0] & 0xFE) == 0xFC;
        }
        return isCarrierGradeNat(addr);
    }

    /**
     * Parses a numeric IPv4/IPv6 address, returning null for anything else.
     * Deliberately does not fall back to {@link InetAddress#getByName(String)}
     * for names, which would resolve them over the network.
     */
    private static InetAddress parseNumeric(String value) {
        try {
            if (value.indexOf(':') >= 0) {
                // IPv6 literal; drop any zone index (fe80::1%wlan0).
                int zone = value.indexOf('%');
                String literal = (zone < 0 ? value : value.substring(0, zone));
                // getByName() falls back to a name lookup for anything it cannot
                // parse numerically — a blocking resolve on the caller's thread.
                // Only hand it strings that can be IPv6 literals in the first
                // place; everything else is not an address we care about.
                if (!isIpv6Literal(literal))
                    return null;
                return InetAddress.getByName(literal);
            }

            String[] parts = value.split("\\.", -1);
            if (parts.length != 4)
                return null;
            byte[] bytes = new byte[4];
            for (int i = 0; i < 4; i++) {
                if (parts[i].isEmpty() || parts[i].length() > 3)
                    return null;
                for (int c = 0; c < parts[i].length(); c++)
                    if (parts[i].charAt(c) < '0' || parts[i].charAt(c) > '9')
                        return null;
                int octet = Integer.parseInt(parts[i]);
                if (octet > 255)
                    return null;
                bytes[i] = (byte) octet;
            }
            return InetAddress.getByAddress(bytes);
        } catch (Throwable ex) {
            Log.w(TAG, "Cannot parse address: " + ex);
            return null;
        }
    }

    /**
     * Whether {@code value} consists only of characters an IPv6 literal can be
     * made of. A cheap gate in front of {@link InetAddress#getByName(String)},
     * which would otherwise resolve whatever it cannot parse.
     */
    private static boolean isIpv6Literal(String value) {
        if (value.isEmpty())
            return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean allowed = (c >= '0' && c <= '9') ||
                    (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F') ||
                    c == ':' || c == '.'; // '.' for IPv4-mapped (::ffff:10.0.0.1)
            if (!allowed)
                return false;
        }
        return true;
    }

    /** 100.64.0.0/10 (RFC 6598), used by some routers for their LAN side. */
    private static boolean isCarrierGradeNat(InetAddress addr) {
        if (!(addr instanceof Inet4Address))
            return false;
        byte[] bytes = addr.getAddress();
        return (bytes[0] & 0xFF) == 100 && (bytes[1] & 0xC0) == 0x40;
    }

    /** Whether {@code url}'s host is a local network address literal. */
    static boolean isLocalUrlHost(String url) {
        if (TextUtils.isEmpty(url))
            return false;
        try {
            String host = URI.create(url.trim()).getHost();
            if (host == null)
                return false;
            // URI keeps the brackets around IPv6 literals.
            if (host.startsWith("[") && host.endsWith("]"))
                host = host.substring(1, host.length() - 1);
            return isLocalAddress(host);
        } catch (Throwable ex) {
            Log.w(TAG, "Cannot parse URL: " + ex);
            return false;
        }
    }

    /** Whether any peer of {@code config} has an endpoint on the local network. */
    static boolean hasLocalWireGuardEndpoint(String config) {
        if (TextUtils.isEmpty(config))
            return false;
        try {
            WgConfig parsed = WgConfigParser.INSTANCE.parse(config);
            for (WgPeer peer : parsed.getPeers()) {
                String endpoint = peer.getEndpoint();
                if (endpoint == null)
                    continue;
                if (isLocalAddress(hostOfEndpoint(endpoint)))
                    return true;
            }
        } catch (Throwable ex) {
            Log.w(TAG, "Cannot parse WireGuard config: " + ex);
        }
        return false;
    }

    /** Strips the port from a WireGuard {@code host:port} endpoint. */
    private static String hostOfEndpoint(String endpoint) {
        String value = endpoint.trim();
        if (value.startsWith("[")) { // [fd00::1]:51820
            int end = value.indexOf(']');
            return end < 0 ? value : value.substring(1, end);
        }
        int colon = value.lastIndexOf(':');
        // A bare IPv6 literal has several colons and no port.
        if (colon < 0 || value.indexOf(':') != colon)
            return value;
        return value.substring(0, colon);
    }
}
