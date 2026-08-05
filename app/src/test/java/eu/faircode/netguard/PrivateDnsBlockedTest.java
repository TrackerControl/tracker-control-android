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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import androidx.preference.PreferenceManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * Which Private DNS configurations leave the device with no working resolver.
 *
 * <p>Only "hostname" mode does: Android falls back to plaintext DNS when a
 * resolver it picked itself cannot be reached over DoT, but not when the user
 * named one explicitly, so blocking port 853 stops resolution outright.
 */
@RunWith(RobolectricTestRunner.class)
public class PrivateDnsBlockedTest {
    private Context context;
    private SharedPreferences prefs;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().clear().commit();
        setPrivateDns("off", null);
    }

    private void setPrivateDns(String mode, String specifier) {
        Settings.Global.putString(context.getContentResolver(), "private_dns_mode", mode);
        Settings.Global.putString(context.getContentResolver(), "private_dns_specifier", specifier);
    }

    @Test
    public void pinnedResolverWithDotBlockingBreaksResolution() {
        setPrivateDns("hostname", "dns.google");
        assertTrue(Util.isPrivateDnsBlocked(context));
    }

    @Test
    public void automaticModeDoesNotBreakResolution() {
        // Android falls back to plaintext on its own, which is the whole reason
        // the onboarding slide could go.
        setPrivateDns("opportunistic", null);
        assertFalse(Util.isPrivateDnsBlocked(context));
    }

    @Test
    public void privateDnsOffDoesNotBreakResolution() {
        setPrivateDns("off", null);
        assertFalse(Util.isPrivateDnsBlocked(context));
    }

    @Test
    public void researchModeLeavesPinnedResolversAlone() {
        // Research mode turns DoT blocking off, so a pinned resolver still works.
        setPrivateDns("hostname", "dns.google");
        prefs.edit().putBoolean("block_dot", false).commit();
        assertFalse(Util.isPrivateDnsBlocked(context));
    }

    /**
     * The decision must not hinge on reading the specifier: if that read comes
     * back empty while the mode says "hostname", resolution is still broken and
     * staying silent would leave the user with no explanation at all.
     */
    @Test
    public void pinnedResolverWithoutReadableNameStillBreaksResolution() {
        setPrivateDns("hostname", null);
        assertTrue(Util.isPrivateDnsBlocked(context));
        assertNull(Util.getPrivateDnsSpecifier(context));
    }

    @Test
    public void specifierIsOnlyReportedForPinnedResolvers() {
        setPrivateDns("hostname", "dns.google");
        assertEquals("dns.google", Util.getPrivateDnsSpecifier(context));

        setPrivateDns("opportunistic", "dns.google");
        assertNull(Util.getPrivateDnsSpecifier(context));
    }
}
