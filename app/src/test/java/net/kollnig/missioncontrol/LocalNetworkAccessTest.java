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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * Which configurations make TrackerControl talk to the local network, and thus
 * need the Android 17 {@code ACCESS_LOCAL_NETWORK} permission (#701). The SDK
 * is pinned to API 36, below the enforcement level, so forwarded-destination
 * tests assert the observation latch directly and keep {@code isMissing()} false.
 */
@RunWith(RobolectricTestRunner.class)
public class LocalNetworkAccessTest {
    private static final String KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final String WG_CONFIG_TEMPLATE =
            "[Interface]\n" +
                    "PrivateKey = " + KEY + "\n" +
                    "Address = 10.64.0.2/32\n" +
                    "\n" +
                    "[Peer]\n" +
                    "PublicKey = " + KEY + "\n" +
                    "AllowedIPs = 0.0.0.0/0\n" +
                    "Endpoint = %s\n";

    private SharedPreferences prefs;

    @Before
    public void setUp() {
        prefs = PreferenceManager.getDefaultSharedPreferences(RuntimeEnvironment.getApplication());
        prefs.edit().clear().commit();
        LocalNetworkAccess.forgetObservations();
        LocalNetworkAccess.refreshPermission(false);
    }

    @Test
    public void runtimeDestinationOnTheLanIsRemembered() {
        assertFalse(LocalNetworkAccess.hasObservedLocalDestination());

        // What a host name in a DoH URL or WireGuard endpoint resolved to.
        LocalNetworkAccess.reportDestination("192.168.1.10");
        assertTrue(LocalNetworkAccess.hasObservedLocalDestination());
    }

    @Test
    public void runtimeDestinationOffTheLanIsIgnored() {
        LocalNetworkAccess.reportDestination("9.9.9.9");
        assertFalse(LocalNetworkAccess.hasObservedLocalDestination());

        // Never resolved here — callers pass an address they already have.
        LocalNetworkAccess.reportDestination("pi.hole");
        assertFalse(LocalNetworkAccess.hasObservedLocalDestination());

        LocalNetworkAccess.reportDestination(null);
        assertFalse(LocalNetworkAccess.hasObservedLocalDestination());
    }

    @Test
    public void observationsAreForgottenOnReset() {
        LocalNetworkAccess.reportDestination("fd00::1");
        assertTrue(LocalNetworkAccess.hasObservedLocalDestination());

        LocalNetworkAccess.forgetObservations();
        assertFalse(LocalNetworkAccess.hasObservedLocalDestination());
    }

    @Test
    public void localForwardedDestinationIsRememberedBelowEnforcement() {
        LocalNetworkAccess.reportForwardedDestination("192.168.1.10");
        assertTrue(LocalNetworkAccess.hasObservedLocalDestination());
        assertFalse(LocalNetworkAccess.isMissing(RuntimeEnvironment.getApplication()));
    }

    @Test
    public void publicForwardedDestinationDoesNotLatchObservation() {
        LocalNetworkAccess.reportForwardedDestination("9.9.9.9");
        assertFalse(LocalNetworkAccess.hasObservedLocalDestination());
        assertFalse(LocalNetworkAccess.isMissing(RuntimeEnvironment.getApplication()));
    }

    @Test
    public void forwardedDestinationObservationIsClearedOnReset() {
        LocalNetworkAccess.reportForwardedDestination("192.168.1.10");
        assertTrue(LocalNetworkAccess.hasObservedLocalDestination());

        LocalNetworkAccess.forgetObservations();
        assertFalse(LocalNetworkAccess.hasObservedLocalDestination());
        assertFalse(LocalNetworkAccess.isMissing(RuntimeEnvironment.getApplication()));
    }

    @Test
    public void forwardedGateIsOpenWhileThePermissionIsMissing() {
        // The gate the packet path consults: it must actually be satisfiable
        // for a plain install, with nothing configured and nothing observed.
        LocalNetworkAccess.refreshPermission(true);
        assertTrue(LocalNetworkAccess.shouldReportForwardedDestination());

        LocalNetworkAccess.reportForwardedDestination("192.168.1.10");
        assertTrue(LocalNetworkAccess.hasObservedLocalDestination());

        // Once latched there is nothing left to learn, so the per-flow check
        // stops parsing addresses.
        assertFalse(LocalNetworkAccess.shouldReportForwardedDestination());
    }

    @Test
    public void forwardedGateStaysClosedWhileThePermissionIsHeld() {
        LocalNetworkAccess.refreshPermission(false);
        assertFalse(LocalNetworkAccess.shouldReportForwardedDestination());
    }

    @Test
    public void observationDoesNotAffectConfigurationDetection() {
        // isConfigured() answers "does the stored configuration name the LAN",
        // which a runtime observation must not fake up.
        LocalNetworkAccess.reportDestination("192.168.1.10");
        assertFalse(LocalNetworkAccess.isConfigured(prefs));
    }

    @Test
    public void defaultConfigurationDoesNotNeedLocalNetworkAccess() {
        assertFalse(LocalNetworkAccess.isConfigured(prefs));
    }

    @Test
    public void publicCustomDnsDoesNotNeedLocalNetworkAccess() {
        prefs.edit().putString("dns", "9.9.9.9").commit();
        assertFalse(LocalNetworkAccess.isConfigured(prefs));
    }

    @Test
    public void privateCustomDnsNeedsLocalNetworkAccess() {
        prefs.edit().putString("dns", "192.168.1.10").commit();
        assertTrue(LocalNetworkAccess.isConfigured(prefs));
    }

    @Test
    public void privateSecondaryDnsNeedsLocalNetworkAccess() {
        prefs.edit().putString("dns", "9.9.9.9").putString("dns2", " 10.0.0.53 ").commit();
        assertTrue(LocalNetworkAccess.isConfigured(prefs));
    }

    @Test
    public void uniqueLocalIpv6DnsNeedsLocalNetworkAccess() {
        prefs.edit().putString("dns", "fd12:3456:789a::1").commit();
        assertTrue(LocalNetworkAccess.isConfigured(prefs));
    }

    @Test
    public void loopbackDnsDoesNotNeedLocalNetworkAccess() {
        // The device itself is not the local network.
        prefs.edit().putString("dns", "127.0.0.1").commit();
        assertFalse(LocalNetworkAccess.isConfigured(prefs));
    }

    @Test
    public void localDohEndpointNeedsLocalNetworkAccessOnlyWhenEnabled() {
        prefs.edit().putString("doh_endpoint", "https://192.168.1.10/dns-query").commit();
        assertFalse(LocalNetworkAccess.isConfigured(prefs));

        prefs.edit().putBoolean("doh_enabled", true).commit();
        assertTrue(LocalNetworkAccess.isConfigured(prefs));
    }

    @Test
    public void bracketedIpv6DohEndpointNeedsLocalNetworkAccess() {
        prefs.edit()
                .putBoolean("doh_enabled", true)
                .putString("doh_endpoint", "https://[fd00::1]:8443/dns-query")
                .commit();
        assertTrue(LocalNetworkAccess.isConfigured(prefs));
    }

    @Test
    public void publicDohEndpointDoesNotNeedLocalNetworkAccess() {
        prefs.edit()
                .putBoolean("doh_enabled", true)
                .putString("doh_endpoint", "https://dns.quad9.net/dns-query")
                .commit();
        assertFalse(LocalNetworkAccess.isConfigured(prefs));
    }

    @Test
    public void tetheringCompatibilityModeNeedsLocalNetworkAccess() {
        // Full-tunnel routes put LAN traffic back inside the tun.
        prefs.edit().putBoolean("tcp_mss_clamp", true).commit();
        assertTrue(LocalNetworkAccess.isConfigured(prefs));
    }

    @Test
    public void remoteWireGuardEndpointDoesNotNeedLocalNetworkAccess() {
        prefs.edit()
                .putBoolean("wg_enabled", true)
                .putString("wg_config", String.format(WG_CONFIG_TEMPLATE, "185.65.135.72:51820"))
                .commit();
        assertFalse(LocalNetworkAccess.isConfigured(prefs));
    }

    @Test
    public void selfHostedWireGuardEndpointNeedsLocalNetworkAccessOnlyWhenEnabled() {
        prefs.edit()
                .putString("wg_config", String.format(WG_CONFIG_TEMPLATE, "192.168.1.5:51820"))
                .commit();
        assertFalse(LocalNetworkAccess.isConfigured(prefs));

        prefs.edit().putBoolean("wg_enabled", true).commit();
        assertTrue(LocalNetworkAccess.isConfigured(prefs));
    }

    @Test
    public void ipv6WireGuardEndpointNeedsLocalNetworkAccess() {
        prefs.edit()
                .putBoolean("wg_enabled", true)
                .putString("wg_config", String.format(WG_CONFIG_TEMPLATE, "[fd00::5]:51820"))
                .commit();
        assertTrue(LocalNetworkAccess.isConfigured(prefs));
    }

    @Test
    public void hostnameEndpointsAreNotTreatedAsLocal() {
        // Hostnames are never resolved here — that would be a network lookup on
        // the caller's thread.
        prefs.edit()
                .putBoolean("wg_enabled", true)
                .putString("wg_config", String.format(WG_CONFIG_TEMPLATE, "vpn.example.org:51820"))
                .commit();
        assertFalse(LocalNetworkAccess.isConfigured(prefs));
    }

    @Test
    public void malformedValuesAreIgnored() {
        prefs.edit()
                .putString("dns", "not-an-address")
                .putString("dns2", "999.1.1.1")
                .putBoolean("doh_enabled", true)
                .putString("doh_endpoint", "not a url")
                .putBoolean("wg_enabled", true)
                .putString("wg_config", "garbage")
                .commit();
        assertFalse(LocalNetworkAccess.isConfigured(prefs));
    }

    @Test
    public void localSocks5ProxyNeedsLocalNetworkAccessOnlyWhenEnabled() {
        prefs.edit().putString("socks5_addr", "192.168.1.2").commit();
        assertFalse(LocalNetworkAccess.isConfigured(prefs));

        prefs.edit().putBoolean("socks5_enabled", true).commit();
        assertTrue(LocalNetworkAccess.isConfigured(prefs));
    }

    @Test
    public void remoteSocks5ProxyDoesNotNeedLocalNetworkAccess() {
        prefs.edit()
                .putBoolean("socks5_enabled", true)
                .putString("socks5_addr", "203.0.113.5")
                .commit();
        assertFalse(LocalNetworkAccess.isConfigured(prefs));
    }

    @Test
    public void privateRangesAreRecognised() {
        assertTrue(LocalNetworkAccess.isLocalAddress("10.0.0.1"));
        assertTrue(LocalNetworkAccess.isLocalAddress("172.16.0.1"));
        assertTrue(LocalNetworkAccess.isLocalAddress("172.31.255.254"));
        assertTrue(LocalNetworkAccess.isLocalAddress("192.168.0.1"));
        assertTrue(LocalNetworkAccess.isLocalAddress("169.254.1.1")); // link-local
        assertTrue(LocalNetworkAccess.isLocalAddress("fe80::1"));
        assertTrue(LocalNetworkAccess.isLocalAddress("fe80::1%wlan0")); // zone index
        assertTrue(LocalNetworkAccess.isLocalAddress("fdff:ffff::1"));
    }

    @Test
    public void publicRangesAreNotRecognisedAsLocal() {
        assertFalse(LocalNetworkAccess.isLocalAddress("172.15.0.1")); // just below 172.16/12
        assertFalse(LocalNetworkAccess.isLocalAddress("172.32.0.1")); // just above
        assertFalse(LocalNetworkAccess.isLocalAddress("192.169.0.1"));
        assertFalse(LocalNetworkAccess.isLocalAddress("2606:4700:4700::1111"));
        assertFalse(LocalNetworkAccess.isLocalAddress("::1")); // the device itself
    }

    @Test
    public void carrierGradeNatRangeIsRecognised() {
        // 100.64.0.0/10 (RFC 6598): some routers use it on their LAN side.
        assertTrue(LocalNetworkAccess.isLocalAddress("100.64.0.1"));
        assertTrue(LocalNetworkAccess.isLocalAddress("100.127.255.254"));
        assertFalse(LocalNetworkAccess.isLocalAddress("100.63.255.255"));
        assertFalse(LocalNetworkAccess.isLocalAddress("100.128.0.1"));
    }

    @Test
    public void hostnamesAreNeverResolved() {
        // A blocking lookup here would run on the caller's thread.
        assertFalse(LocalNetworkAccess.isLocalAddress("pi.hole"));
        assertFalse(LocalNetworkAccess.isLocalAddress("localhost"));
        assertFalse(LocalNetworkAccess.isLocalAddress("not:a:literal"));
        assertFalse(LocalNetworkAccess.isLocalUrlHost("https://pi.hole/dns-query"));
    }

    @Test
    public void wireGuardEndpointWithoutPortIsRecognised() {
        prefs.edit()
                .putBoolean("wg_enabled", true)
                .putString("wg_config", String.format(WG_CONFIG_TEMPLATE, "192.168.1.5"))
                .commit();
        assertTrue(LocalNetworkAccess.isConfigured(prefs));
    }

    @Test
    public void relevantSettingsAreRecognised() {
        assertTrue(LocalNetworkAccess.isRelevantSetting("dns"));
        assertTrue(LocalNetworkAccess.isRelevantSetting("doh_endpoint"));
        assertTrue(LocalNetworkAccess.isRelevantSetting("tcp_mss_clamp"));
        assertTrue(LocalNetworkAccess.isRelevantSetting("wg_config"));
        assertTrue(LocalNetworkAccess.isRelevantSetting("socks5_addr"));
        assertFalse(LocalNetworkAccess.isRelevantSetting("blocking_mode"));
        assertFalse(LocalNetworkAccess.isRelevantSetting(null));
    }
}
