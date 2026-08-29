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

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.Set;

import net.kollnig.missioncontrol.data.InternetBlocklist;
import net.kollnig.missioncontrol.data.TrackerBlocklist;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class ActivitySettingsTest {
    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences(TrackerBlocklist.PREF_BLOCKLIST, Context.MODE_PRIVATE)
                .edit().clear().commit();
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
}
