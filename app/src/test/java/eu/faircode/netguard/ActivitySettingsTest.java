/*
 * This file is part of TrackerControl.
 *
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package eu.faircode.netguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Xml;

import androidx.preference.EditTextPreference;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;

import java.io.StringWriter;
import java.util.Collections;
import java.util.Set;

import org.xmlpull.v1.XmlSerializer;

import net.kollnig.missioncontrol.data.InternetBlocklist;
import net.kollnig.missioncontrol.data.TrackerBlocklist;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class ActivitySettingsTest {
    private static final String DOH_ENDPOINT_KEY = "activity_settings_test_doh_endpoint";

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences(TrackerBlocklist.PREF_BLOCKLIST, Context.MODE_PRIVATE)
                .edit().clear().commit();
        PreferenceManager.getDefaultSharedPreferences(context).edit().remove(DOH_ENDPOINT_KEY).commit();
        TrackerBlocklist.getInstance(null).clear();
        InternetBlocklist.getInstance(null).clear();
    }

    @Test
    public void unresolvedImportedPackageIsRetainedForBothUidStores() {
        String packageName = "com.example.importedlater";

        Set<String> expected = Collections.singleton(packageName);

        assertEquals(expected, ActivitySettings.resolveImportedUids(context, packageName));
        assertEquals(expected, ActivitySettings.resolveImportedUids(context, packageName));

        SharedPreferences prefs = context
                .getSharedPreferences(TrackerBlocklist.PREF_BLOCKLIST, Context.MODE_PRIVATE);
        prefs
                .edit()
                .putStringSet(InternetBlocklist.SHARED_PREFS_INTERNET_BLOCKLIST_APPS_KEY, expected)
                .putStringSet(TrackerBlocklist.SHARED_PREFS_BLOCKLIST_APPS_KEY, expected)
                .commit();

        InternetBlocklist internetBlocklist = InternetBlocklist.getInstance(context);
        internetBlocklist.loadSettings(context);
        TrackerBlocklist trackerBlocklist = TrackerBlocklist.getInstance(context);
        assertFalse(internetBlocklist.blockedInternet(1001));
        assertFalse(trackerBlocklist.hasSubset(1001));

        assertFalse(internetBlocklist.resolvePendingPackages(context));
    }

    @Test
    public void exportCarriesAnUnresolvedPackagesSubset() throws Exception {
        String packageName = "com.example.importedlater";
        SharedPreferences prefs = context
                .getSharedPreferences(TrackerBlocklist.PREF_BLOCKLIST, Context.MODE_PRIVATE);
        prefs.edit()
                .putStringSet(TrackerBlocklist.SHARED_PREFS_BLOCKLIST_APPS_KEY + "_" + packageName,
                        Collections.singleton("Advertising | Example"))
                .commit();

        StringWriter writer = new StringWriter();
        XmlSerializer serializer = Xml.newSerializer();
        serializer.setOutput(writer);
        serializer.startDocument("UTF-8", true);
        serializer.startTag(null, "application");
        // A pending entry is keyed by package name on both sides, so it is
        // exported verbatim rather than dropped for failing to parse as a UID.
        ActivitySettings.exportTrackerSubset(prefs, serializer, packageName, packageName);
        serializer.endTag(null, "application");
        serializer.endDocument();

        String xml = writer.toString();
        assertTrue(xml, xml.contains("APPS_BLOCKLIST_PACKAGES_KEY_" + packageName));
        assertTrue(xml, xml.contains("Advertising | Example"));
    }

    @Test
    public void dohEndpointListenerAcceptsProviderPathsQueriesAndNormalisesWhitespace() {
        EditTextPreference preference = dohEndpointPreference("https://old.example/dns-query");

        assertTrue(applyDohEndpoint(preference, "https://dns.quad9.net/dns-query"));
        assertEquals("https://dns.quad9.net/dns-query", preference.getText());
        assertEquals("https://dns.quad9.net/dns-query", dohPreferences().getString(DOH_ENDPOINT_KEY, null));

        assertTrue(applyDohEndpoint(preference, "https://dns.example"));
        assertEquals("https://dns.example", preference.getText());
        assertTrue(applyDohEndpoint(preference, "https://dns.example/dns-query?ct=application/dns-message"));
        assertEquals("https://dns.example/dns-query?ct=application/dns-message", preference.getText());
        assertTrue(applyDohEndpoint(preference, "https://例え.テスト/dns-query"));
        assertEquals("https://例え.テスト/dns-query", preference.getText());

        assertFalse(applyDohEndpoint(preference, "  https://dns.example/dns-query  "));
        assertEquals("https://dns.example/dns-query", preference.getText());
        assertEquals("https://dns.example/dns-query", dohPreferences().getString(DOH_ENDPOINT_KEY, null));
    }

    @Test
    public void dohEndpointListenerRejectsInvalidEditsAndPreservesPreviousValue() {
        EditTextPreference preference = dohEndpointPreference("https://dns.example/dns-query");
        String[] invalidEndpoints = {
                "",
                "  ",
                "http://dns.example/dns-query",
                "https:///dns-query",
                "https://dns.example:invalid/dns-query",
                "https://dns.example:65536/dns-query",
                "https://user@dns.example/dns-query",
                "https://user@例え.テスト/dns-query",
                "https://dns.example/dns-query#fragment",
                "https://dns. example/dns-query"
        };

        for (String invalidEndpoint : invalidEndpoints) {
            assertFalse(invalidEndpoint, applyDohEndpoint(preference, invalidEndpoint));
            assertEquals(invalidEndpoint, "https://dns.example/dns-query", preference.getText());
            assertEquals(invalidEndpoint, "https://dns.example/dns-query",
                    dohPreferences().getString(DOH_ENDPOINT_KEY, null));
        }
    }

    private EditTextPreference dohEndpointPreference(String initialEndpoint) {
        PreferenceManager manager = new PreferenceManager(context);
        PreferenceScreen screen = manager.createPreferenceScreen(context);
        EditTextPreference preference = new EditTextPreference(context);
        preference.setKey(DOH_ENDPOINT_KEY);
        screen.addPreference(preference);
        manager.setPreferences(screen);
        preference.setText(initialEndpoint);
        ActivitySettings.configureDohEndpointPreference(preference);
        return preference;
    }

    private boolean applyDohEndpoint(EditTextPreference preference, String endpoint) {
        boolean accepted = preference.callChangeListener(endpoint);
        if (accepted)
            preference.setText(endpoint);
        return accepted;
    }

    private SharedPreferences dohPreferences() {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }
}
