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

package net.kollnig.missioncontrol.analysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class TrackerAnalysisCacheClearTest {
    private static final String PREFS_NAME = "library_analysis";
    private static final String PACKAGE = "org.example.gone";
    private static final String OTHER_PACKAGE = "org.example.stays";

    private SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private void seed(Context context, String packageName) {
        prefs(context).edit()
                .putString("trackers_" + packageName, "\n• Google")
                .putInt("versioncode_" + packageName, 7)
                .putInt("attempted_versioncode_" + packageName, 7)
                .apply();
    }

    @Test
    public void clearCacheRemovesEveryKeyForThePackage() {
        Context context = RuntimeEnvironment.getApplication();
        seed(context, PACKAGE);

        TrackerAnalysisManager.clearCache(context, PACKAGE);

        SharedPreferences prefs = prefs(context);
        assertNull(prefs.getString("trackers_" + PACKAGE, null));
        assertFalse(prefs.contains("versioncode_" + PACKAGE));
        // The attempted marker must go too: leaving it behind would suppress
        // the automatic re-analysis after a reinstall of the same version.
        assertFalse(prefs.contains("attempted_versioncode_" + PACKAGE));
    }

    @Test
    public void clearCacheLeavesOtherPackagesAlone() {
        Context context = RuntimeEnvironment.getApplication();
        seed(context, PACKAGE);
        seed(context, OTHER_PACKAGE);

        TrackerAnalysisManager.clearCache(context, PACKAGE);

        SharedPreferences prefs = prefs(context);
        assertEquals("\n• Google", prefs.getString("trackers_" + OTHER_PACKAGE, null));
        assertTrue(prefs.contains("versioncode_" + OTHER_PACKAGE));
        assertTrue(prefs.contains("attempted_versioncode_" + OTHER_PACKAGE));
    }

    @Test
    public void clearCacheIsSafeForAPackageThatWasNeverAnalysed() {
        Context context = RuntimeEnvironment.getApplication();

        TrackerAnalysisManager.clearCache(context, "org.example.unknown");

        assertNull(prefs(context).getString("trackers_org.example.unknown", null));
    }
}
