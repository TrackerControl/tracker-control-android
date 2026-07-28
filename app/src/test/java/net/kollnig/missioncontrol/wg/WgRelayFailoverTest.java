package net.kollnig.missioncontrol.wg;

import static org.junit.Assert.assertFalse;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Covers the guard clauses in {@link WgRelayFailover#attemptFailover} that
 * don't require network access: no active profile, no provider (self-hosted
 * or manually imported config), and an unrecognized provider string. The
 * provider-specific success paths need a live relay-list fetch and aren't
 * covered here.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, qualifiers = "en")
public class WgRelayFailoverTest {
    private Context context;
    private WgProfileManager manager;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().clear().commit();
        manager = new WgProfileManager(context);
    }

    @Test
    public void noopWhenNoActiveProfile() {
        assertFalse(WgRelayFailover.attemptFailover(context));
    }

    @Test
    public void noopForSelfHostedProfileWithoutProvider() throws Exception {
        manager.saveProfile("", "Home server", "[Interface]\nPrivateKey = k\n[Peer]\nPublicKey = p\n");

        assertFalse(WgRelayFailover.attemptFailover(context));
    }

    @Test
    public void noopForUnrecognizedProvider() throws Exception {
        manager.saveProfile("", "Other", "config", "wireguard-other", "account");

        assertFalse(WgRelayFailover.attemptFailover(context));
    }

    @Test
    public void noopWhenActiveConfigIsEmpty() throws Exception {
        manager.saveProfile("", "Mullvad", "", "mullvad", "account");

        assertFalse(WgRelayFailover.attemptFailover(context));
    }
}
