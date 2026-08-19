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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RemoteRoutingLogicTest {

    /**
     * The shipped default has to leave the pre-feature behaviour untouched:
     * everything tunnels, including unknown-UID and system traffic.
     */
    @Test
    public void defaultModeTunnelsEverything() {
        assertEquals(RemoteRoutingLogic.MODE_ALL, RemoteRoutingLogic.getDefaultMode());
        assertTrue(RemoteRoutingLogic.defaultTunnel(RemoteRoutingLogic.MODE_ALL));
        assertTrue(RemoteRoutingLogic.routesThroughTunnel(
                RemoteRoutingLogic.MODE_ALL, null, true));
    }

    @Test
    public void selectedModeKeepsUnconfiguredAppsDirect() {
        assertFalse(RemoteRoutingLogic.defaultTunnel(RemoteRoutingLogic.MODE_SELECTED));
        assertFalse(RemoteRoutingLogic.routesThroughTunnel(
                RemoteRoutingLogic.MODE_SELECTED, null, true));
        assertTrue(RemoteRoutingLogic.routesThroughTunnel(
                RemoteRoutingLogic.MODE_SELECTED, Boolean.TRUE, true));
    }

    @Test
    public void overrideWinsOverModeInBothDirections() {
        assertFalse(RemoteRoutingLogic.routesThroughTunnel(
                RemoteRoutingLogic.MODE_ALL, Boolean.FALSE, true));
        assertTrue(RemoteRoutingLogic.routesThroughTunnel(
                RemoteRoutingLogic.MODE_SELECTED, Boolean.TRUE, true));
    }

    /**
     * A bypassed app never reaches the tun, so it can never be tunnelled —
     * whatever its stale override says.
     */
    @Test
    public void bypassedAppNeverTunnels() {
        for (String mode : new String[] { RemoteRoutingLogic.MODE_ALL,
                RemoteRoutingLogic.MODE_SELECTED })
            for (Boolean override : new Boolean[] { null, Boolean.TRUE, Boolean.FALSE })
                assertFalse(RemoteRoutingLogic.routesThroughTunnel(mode, override, false));
    }

    @Test
    public void unknownModeFallsBackToTheDefault() {
        assertEquals(RemoteRoutingLogic.MODE_ALL, RemoteRoutingLogic.normalizeMode(null));
        assertEquals(RemoteRoutingLogic.MODE_ALL, RemoteRoutingLogic.normalizeMode("nonsense"));
        assertTrue(RemoteRoutingLogic.defaultTunnel("nonsense"));
    }

    @Test
    public void controlNeedsRemoteVpnDefaultRoutesAndANonBypassedApp() {
        assertTrue(RemoteRoutingLogic.isControlAvailable(true, true, true));
        assertFalse(RemoteRoutingLogic.isControlAvailable(false, true, true));
        assertFalse(RemoteRoutingLogic.isControlAvailable(true, false, true));
        assertFalse(RemoteRoutingLogic.isControlAvailable(true, true, false));
    }

    @Test
    public void unavailableReasonNamesTheBlockingCondition() {
        assertEquals(RemoteRoutingLogic.Unavailable.NO_REMOTE_VPN,
                RemoteRoutingLogic.getUnavailableReason(false, true, true));
        assertEquals(RemoteRoutingLogic.Unavailable.BYPASSED,
                RemoteRoutingLogic.getUnavailableReason(true, true, false));
        assertEquals(RemoteRoutingLogic.Unavailable.PARTIAL_ROUTES,
                RemoteRoutingLogic.getUnavailableReason(true, false, true));
        assertNull(RemoteRoutingLogic.getUnavailableReason(true, true, true));
    }

    /**
     * The DNS redirect costs a UID lookup, a JNI upcall and a UDP session per
     * query, so nobody pays for it until an app is actually routed direct.
     * Keying this off the mode instead was wrong: a per-app override sends an
     * app direct in "All apps" too, and that app then tunnelled its DNS while
     * its traffic went direct — the very split the redirect exists to avoid.
     */
    @Test
    public void dnsRedirectFollowsActualDirectApps() {
        assertFalse(RemoteRoutingLogic.redirectDirectDns(false));
        assertTrue(RemoteRoutingLogic.redirectDirectDns(true));

        boolean overriddenDirectInAllMode = !RemoteRoutingLogic.routesThroughTunnel(
                RemoteRoutingLogic.MODE_ALL, Boolean.FALSE, true);
        assertTrue(RemoteRoutingLogic.redirectDirectDns(overriddenDirectInAllMode));
    }

    /**
     * An app is only ever pushed into the override set when its routing
     * actually differs from the global default; matching the default in
     * either direction is not an override.
     */
    @Test
    public void isRouteOverrideOnlyWhenTunnelledDiffersFromDefault() {
        assertFalse(RemoteRoutingLogic.isRouteOverride(true, true));
        assertFalse(RemoteRoutingLogic.isRouteOverride(false, false));
        assertTrue(RemoteRoutingLogic.isRouteOverride(true, false));
        assertTrue(RemoteRoutingLogic.isRouteOverride(false, true));
    }

    /**
     * In the shipped default mode ("all", so defaultTunnel is true) with no
     * per-app override configured, nothing is an override. That is the
     * property that keeps the pushed set empty and lets the native fast path
     * engage for everyone who never touches per-app routing.
     */
    @Test
    public void defaultModeWithNoOverrideProducesNoOverride() {
        assertFalse(RemoteRoutingLogic.isRouteOverride(true, true));
    }

    /**
     * routesDirect mirrors the native is_tunnel_uid: a UID absent from the
     * override set follows the global default in both directions, and a UID
     * present in the set always gets the opposite of the default.
     */
    @Test
    public void routesDirectCoversAllFourCombinations() {
        // Not in the override set: follows the default, whatever it is.
        assertFalse(RemoteRoutingLogic.routesDirect(false, true));
        assertTrue(RemoteRoutingLogic.routesDirect(false, false));

        // In the override set: inverts the default.
        assertTrue(RemoteRoutingLogic.routesDirect(true, true));
        assertFalse(RemoteRoutingLogic.routesDirect(true, false));
    }
}
