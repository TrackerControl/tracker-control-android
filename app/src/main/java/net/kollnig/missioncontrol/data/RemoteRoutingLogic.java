/*
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
 * Copyright © 2026
 */

package net.kollnig.missioncontrol.data;

/**
 * Decides which apps are forwarded through the remote WireGuard tunnel.
 * <p>
 * Whether an app is <em>filtered</em> by TrackerControl and whether it is
 * <em>forwarded through the remote VPN</em> are independent choices. Before
 * this existed the only way to keep an app off the remote tunnel was to
 * exclude it from the VPN altogether, which also dropped local monitoring and
 * blocking (#723).
 * <p>
 * Pure helpers, no Android dependencies, so the decision table is JVM-testable
 * — the native packet path has no test harness at all.
 */
public final class RemoteRoutingLogic {
    /** Every app goes through the remote tunnel. The shipped default. */
    public static final String MODE_ALL = "all";
    /** Only apps with an explicit per-app override go through the tunnel. */
    public static final String MODE_SELECTED = "selected";

    private RemoteRoutingLogic() {
    }

    public static String getDefaultMode() {
        return MODE_ALL;
    }

    public static String normalizeMode(String mode) {
        return MODE_SELECTED.equals(mode) ? MODE_SELECTED : MODE_ALL;
    }

    /**
     * Whether unknown-UID and system traffic takes the tunnel. In
     * {@link #MODE_ALL} this is true, which makes the whole feature a no-op
     * against the behaviour that shipped before it.
     */
    public static boolean defaultTunnel(String mode) {
        return !MODE_SELECTED.equals(normalizeMode(mode));
    }

    /**
     * Whether one app is routed through the remote tunnel.
     *
     * @param mode     the global routing mode
     * @param override the per-app override, or {@code null} when unset
     * @param apply    the app's "apply" preference; a bypassed app is outside
     *                 the tun entirely, so it has no routing to decide
     */
    public static boolean routesThroughTunnel(String mode, Boolean override, boolean apply) {
        if (!apply)
            return false;

        if (override != null)
            return override;

        return defaultTunnel(mode);
    }

    /**
     * Whether the per-app routing control should be offered at all.
     * <p>
     * Routes come from the tunnel's AllowedIPs, and they are a property of the
     * one tun every app shares — narrowing them to route some apps around the
     * tunnel would shrink them for the tunnelled apps too. v1 therefore only
     * offers the control for a default-route tunnel. gotatun silently drops
     * packets whose destination matches no peer's AllowedIPs, so a narrower
     * tunnel would blackhole traffic rather than fail visibly.
     *
     * @param wgEnabled     whether remote egress is configured and on
     * @param defaultRoutes whether AllowedIPs covers 0.0.0.0/0 (and ::/0 when
     *                      IPv6 is enabled)
     * @param apply         the app's "apply" preference
     */
    public static boolean isControlAvailable(boolean wgEnabled, boolean defaultRoutes, boolean apply) {
        return wgEnabled && defaultRoutes && apply;
    }

    /**
     * Why the control is unavailable, for the explanation shown in its place.
     */
    public static Unavailable getUnavailableReason(boolean wgEnabled, boolean defaultRoutes,
            boolean apply) {
        if (!wgEnabled)
            return Unavailable.NO_REMOTE_VPN;
        if (!apply)
            return Unavailable.BYPASSED;
        if (!defaultRoutes)
            return Unavailable.PARTIAL_ROUTES;
        return null;
    }

    /**
     * Whether direct apps' DNS is redirected to the system resolver.
     * <p>
     * Turning this on makes every DNS query cost a UID lookup, an
     * isAddressAllowed upcall and a real UDP session — today port 53 skips all
     * three. It is therefore only enabled once some app actually is routed
     * around the tunnel, so everyone else keeps the existing zero-cost DNS
     * path. Note this is a property of the resolved rules, not of the mode: a
     * per-app override sends an app direct in either mode.
     */
    public static boolean redirectDirectDns(boolean anyAppRoutedDirect) {
        return anyAppRoutedDirect;
    }

    /**
     * Whether one UID's traffic leaves outside the tunnel, mirroring the native
     * is_tunnel_uid.
     * <p>
     * A UID with no rule of its own — unknown or system traffic — follows the
     * global default. Treating it as "not tunnelled" merely because it is
     * absent from the tunnelled set would quietly route system traffic direct.
     *
     * @param inTunnelSet   whether the UID is in the tunnelled set
     * @param known         whether any installed app maps to this UID
     * @param defaultTunnel the global default for unlisted UIDs
     */
    public static boolean routesDirect(boolean inTunnelSet, boolean known, boolean defaultTunnel) {
        if (inTunnelSet)
            return false;
        if (known)
            return true;
        return !defaultTunnel;
    }

    /**
     * Whether the tunnel's AllowedIPs is a default route.
     * <p>
     * Routes are a property of the single tun every app shares, so a narrower
     * AllowedIPs shrinks them for directly-routed apps too. v1 therefore only
     * offers per-app routing for a full-tunnel config.
     *
     * @param allowedIps the union of every peer's AllowedIPs
     * @param ip6        whether IPv6 is enabled, in which case ::/0 is required too
     */
    public static boolean hasDefaultRoutes(java.util.List<String> allowedIps, boolean ip6) {
        boolean v4 = false;
        boolean v6 = false;
        for (String allowedIp : allowedIps) {
            String trimmed = allowedIp == null ? "" : allowedIp.trim();
            if ("0.0.0.0/0".equals(trimmed))
                v4 = true;
            else if ("::/0".equals(trimmed))
                v6 = true;
        }

        return v4 && (!ip6 || v6);
    }

    public enum Unavailable {
        /** No remote VPN is configured or enabled. */
        NO_REMOTE_VPN,
        /** The app bypasses TrackerControl, so it is outside the tun. */
        BYPASSED,
        /** The tunnel's AllowedIPs is not a default route. */
        PARTIAL_ROUTES
    }
}
