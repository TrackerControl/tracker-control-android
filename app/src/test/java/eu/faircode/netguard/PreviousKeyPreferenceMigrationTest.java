package eu.faircode.netguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import net.kollnig.missioncontrol.data.BlockingMode;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, qualifiers = "en")
public class PreviousKeyPreferenceMigrationTest {
    private SharedPreferences prefs;

    @Before
    public void setUp() {
        prefs = PreferenceManager.getDefaultSharedPreferences(RuntimeEnvironment.getApplication());
        prefs.edit().clear().commit();
    }

    @Test
    public void obsoletePreviousKeyPreferencesAreRemoved() {
        prefs.edit()
                .putString("mullvad_previous_privkey", "old-private")
                .putString("mullvad_previous_address", "10.64.0.2/32")
                .putString("ivpn_previous_privkey", "old-private")
                .putString("ivpn_previous_address", "10.64.0.3/32")
                .putString("unrelated_pref", "keep")
                .putBoolean("wg_enabled", true)
                .putString(BlockingMode.PREF_BLOCKING_MODE, BlockingMode.MODE_STRICT)
                .commit();

        ApplicationEx.migratePreferences(prefs);

        assertFalse(prefs.contains("mullvad_previous_privkey"));
        assertFalse(prefs.contains("mullvad_previous_address"));
        assertFalse(prefs.contains("ivpn_previous_privkey"));
        assertFalse(prefs.contains("ivpn_previous_address"));
        assertEquals("keep", prefs.getString("unrelated_pref", ""));
        assertTrue(prefs.getBoolean("wg_enabled", false));
        assertEquals(BlockingMode.MODE_STRICT,
                prefs.getString(BlockingMode.PREF_BLOCKING_MODE, ""));
    }
}
