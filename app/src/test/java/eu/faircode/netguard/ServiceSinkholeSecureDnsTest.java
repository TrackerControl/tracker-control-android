/*
 * This file is part of TrackerControl.
 *
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package eu.faircode.netguard;

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
 * Secure DNS must not be started alongside the userspace WireGuard egress.
 * The egress supplies the VPN DNS path, including its public fallback when a
 * config omits {@code DNS =}; the app process itself is excluded from the VPN.
 */
@RunWith(RobolectricTestRunner.class)
public class ServiceSinkholeSecureDnsTest {
    private static final String KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final String WG_CONFIG =
            "[Interface]\n" +
                    "PrivateKey = " + KEY + "\n" +
                    "Address = 10.64.0.2/32\n" +
                    "%s" +
                    "\n[Peer]\n" +
                    "PublicKey = " + KEY + "\n" +
                    "AllowedIPs = 0.0.0.0/0\n" +
                    "Endpoint = 198.51.100.1:51820\n";

    private SharedPreferences prefs;

    @Before
    public void setUp() {
        prefs = PreferenceManager.getDefaultSharedPreferences(RuntimeEnvironment.getApplication());
        prefs.edit().clear().commit();
    }

    @Test
    public void wireGuardWithoutDnsStillOwnsDnsPath() {
        prefs.edit()
                .putBoolean("wg_enabled", true)
                .putString("wg_config", String.format(WG_CONFIG, ""))
                .commit();

        assertTrue(ServiceSinkhole.hasActiveWireGuard(prefs));
    }

    @Test
    public void wireGuardWithDnsOwnsDnsPath() {
        prefs.edit()
                .putBoolean("wg_enabled", true)
                .putString("wg_config", String.format(WG_CONFIG, "DNS = 10.64.0.1\n"))
                .commit();

        assertTrue(ServiceSinkhole.hasActiveWireGuard(prefs));
    }

    @Test
    public void disabledOrIncompleteWireGuardDoesNotSuppressDoh() {
        prefs.edit().putString("wg_config", String.format(WG_CONFIG, "")).commit();
        assertFalse(ServiceSinkhole.hasActiveWireGuard(prefs));

        prefs.edit().putBoolean("wg_enabled", true).putString("wg_config", "").commit();
        assertFalse(ServiceSinkhole.hasActiveWireGuard(prefs));
    }

    @Test
    public void malformedWireGuardDoesNotSuppressDoh() {
        prefs.edit()
                .putBoolean("wg_enabled", true)
                .putString("wg_config", "not a WireGuard config")
                .commit();

        assertFalse(ServiceSinkhole.hasActiveWireGuard(prefs));
    }
}
