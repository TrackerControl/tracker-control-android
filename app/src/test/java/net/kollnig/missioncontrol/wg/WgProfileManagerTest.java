package net.kollnig.missioncontrol.wg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import net.kollnig.missioncontrol.R;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, qualifiers = "en")
public class WgProfileManagerTest {
    private Context context;
    private SharedPreferences prefs;
    private WgProfileManager manager;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().clear().commit();
        manager = new WgProfileManager(context);
    }

    @Test
    public void migratesLegacyConfigAndMakesItActive() {
        prefs.edit().putString(WgProfileManager.PREF_WG_CONFIG, "legacy-config").commit();

        manager.migrateIfNeeded();

        List<WgProfileManager.Profile> profiles = manager.getProfiles();
        assertEquals(1, profiles.size());
        assertEquals(context.getString(R.string.msg_wg_profile_default_name), profiles.get(0).name);
        assertEquals("legacy-config", profiles.get(0).config);
        assertEquals(profiles.get(0).id, manager.getActiveProfileId());
    }

    @Test
    public void migrationRepairsMissingActiveProfileAndConfig() throws Exception {
        JSONArray profiles = new JSONArray()
                .put(profile("first", "First", "first-config", "", "", "", ""))
                .put(profile("second", "Second", "second-config", "", "", "", ""));
        prefs.edit()
                .putString(WgProfileManager.PREF_WG_PROFILES, profiles.toString())
                .putString(WgProfileManager.PREF_WG_PROFILE, "missing")
                .putString(WgProfileManager.PREF_WG_CONFIG, "stale")
                .commit();

        manager.migrateIfNeeded();

        assertEquals("first", manager.getActiveProfileId());
        assertEquals("first-config", prefs.getString(WgProfileManager.PREF_WG_CONFIG, ""));
    }

    @Test
    public void saveUpdateAndSelectProfilesKeepLegacyConfigInSync() throws Exception {
        manager.saveProfile("", "One", "config-1", "mullvad", " acct ", "NL", "Netherlands");
        String first = manager.getActiveProfileId();
        manager.saveProfile("", "Two", "config-2", "ivpn", "ivpn-account", "DE", "Germany");
        String second = manager.getActiveProfileId();

        assertFalse(first.equals(second));
        manager.saveProfile(first, "Renamed", "config-1b", "mullvad", "acct", "us", "USA");
        assertEquals(first, manager.getActiveProfileId());
        assertEquals("Renamed", manager.getProfile(first).name);
        assertEquals("us", manager.getProfile(first).countryCode);
        assertEquals("config-1b", prefs.getString(WgProfileManager.PREF_WG_CONFIG, ""));

        manager.setActiveProfile(second);
        assertEquals(second, manager.getActiveProfileId());
        assertEquals("config-2", prefs.getString(WgProfileManager.PREF_WG_CONFIG, ""));
        manager.setActiveProfile("does-not-exist");
        assertEquals(second, manager.getActiveProfileId());
    }

    @Test
    public void deletingInactiveProfilePreservesActiveSelection() throws Exception {
        manager.saveProfile("", "One", "config-1");
        String first = manager.getActiveProfileId();
        manager.saveProfile("", "Two", "config-2");
        String second = manager.getActiveProfileId();

        manager.deleteProfile(first);

        assertEquals(second, manager.getActiveProfileId());
        assertEquals("config-2", prefs.getString(WgProfileManager.PREF_WG_CONFIG, ""));
    }

    @Test
    public void deletingActiveSelectsFirstRemainingThenClearsLast() throws Exception {
        manager.saveProfile("", "One", "config-1");
        String first = manager.getActiveProfileId();
        manager.saveProfile("", "Two", "config-2");
        String second = manager.getActiveProfileId();

        manager.deleteProfile(second);
        assertEquals(first, manager.getActiveProfileId());
        assertEquals("config-1", prefs.getString(WgProfileManager.PREF_WG_CONFIG, ""));

        manager.deleteProfile(first);
        assertTrue(manager.getProfiles().isEmpty());
        assertFalse(prefs.contains(WgProfileManager.PREF_WG_PROFILE));
        assertFalse(prefs.contains(WgProfileManager.PREF_WG_CONFIG));
    }

    @Test
    public void providerAndCountryLookupPreferActiveAndNormalizeCountry() throws Exception {
        manager.saveProfile("", "Mullvad NL", "m-nl", "mullvad", "m-account", " NL ", "Netherlands");
        String mullvad = manager.getActiveProfileId();
        manager.saveProfile("", "IVPN NL", "i-nl", "ivpn", "i-account", "Nl", "Netherlands");

        assertEquals(mullvad, manager.findMullvadProfileForCountry("nL").id);
        assertEquals("i-nl", manager.findIvpnProfileForCountry(" NL ").config);
        assertNull(manager.findMullvadProfileForCountry(""));
        assertEquals("i-nl", manager.getProviderConfig("ivpn", "i-account"));
        assertTrue(manager.hasProviderProfiles("mullvad", "m-account"));
        assertFalse(manager.hasProviderProfiles("mullvad", "wrong"));
    }

    @Test
    public void providerAccountsAreRecoveredAndAccountChangesClearCredentials() throws Exception {
        manager.saveProfile("", "Mullvad", "config", "mullvad", "m-account");
        manager.saveProfile("", "IVPN", "config", "ivpn", "i-account");
        assertEquals("i-account", manager.getLastIvpnAccount());
        assertEquals("m-account", manager.getLastMullvadAccount());

        manager.saveMullvadDeviceId(" device ");
        manager.saveMullvadAccount("another");
        assertEquals("", manager.getMullvadDeviceId());

        manager.saveIvpnSession(new WgProfileManager.IvpnSession("token", "private", "public", "10.0.0.1/32"));
        assertNotNull(manager.getIvpnSession("i-account"));
        manager.saveIvpnAccount("different");
        assertNull(manager.getIvpnSession("i-account"));
        assertFalse(prefs.contains(WgProfileManager.PREF_IVPN_SESSION_TOKEN));
    }

    @Test
    public void rewriteProviderInterfaceUpdatesMatchingProfilesAndActiveConfig() throws Exception {
        String config = "[Interface]\nPrivateKey = old\nAddress = 10.0.0.1/32\n" +
                "# keep me\n[Peer]\nPublicKey = peer\nEndpoint = example:51820\n";
        manager.saveProfile("", "Mullvad", config, "mullvad", "account");

        assertTrue(manager.rewriteProviderInterface("mullvad", " account ", "new", "10.0.0.2/32"));
        String updated = manager.getActiveProfile().config;
        assertTrue(updated.contains("PrivateKey = new"));
        assertTrue(updated.contains("Address = 10.0.0.2/32"));
        assertTrue(updated.contains("# keep me"));
        assertEquals(updated, prefs.getString(WgProfileManager.PREF_WG_CONFIG, ""));
        assertFalse(manager.rewriteProviderInterface("mullvad", "account", "new", "10.0.0.2/32"));
    }

    @Test
    public void rewriteAddsMissingInterfaceLinesWithoutChangingNonMatchingProfiles() throws Exception {
        manager.saveProfile("", "Other", "[Interface]\nDNS = 1.1.1.1\n[Peer]\nPublicKey = p", "ivpn", "other");
        String other = manager.getActiveProfileId();
        manager.saveProfile("", "Target", "[Interface]\nDNS = 9.9.9.9\n[Peer]\nPublicKey = p", "ivpn", "target");
        manager.setActiveProfile(other);

        assertFalse(manager.rewriteProviderInterface("ivpn", "target", "private", "10.0.0.3/32"));
        String target = manager.getProviderConfig("ivpn", "target");
        assertTrue(target.contains("[Interface]\nAddress = 10.0.0.3/32\nPrivateKey = private\nDNS = 9.9.9.9"));
        assertEquals("[Interface]\nDNS = 1.1.1.1\n[Peer]\nPublicKey = p", manager.getActiveProfile().config);
    }

    @Test
    public void malformedStoredProfilesAreIgnoredAndCanBeReplacedByLegacyMigration() {
        prefs.edit()
                .putString(WgProfileManager.PREF_WG_PROFILES, "not json")
                .putString(WgProfileManager.PREF_WG_CONFIG, "legacy")
                .commit();

        manager.migrateIfNeeded();

        assertEquals(1, manager.getProfiles().size());
        assertEquals("legacy", manager.getActiveProfile().config);
    }

    private static JSONObject profile(String id, String name, String config, String provider,
                                      String account, String countryCode, String countryName) throws Exception {
        return new JSONObject()
                .put("id", id).put("name", name).put("config", config)
                .put("provider", provider).put("account", account)
                .put("countryCode", countryCode).put("countryName", countryName);
    }
}
