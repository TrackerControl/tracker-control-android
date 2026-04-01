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
 * You should have received a copy of the GNU General Public License
 * along with TrackerControl. If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright © 2019–2020 Konrad Kollnig (University of Oxford)
 */

package net.kollnig.missioncontrol.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import eu.faircode.netguard.Util;

/**
 * Manages the DDG Minimal Blocking mode.
 *
 * In minimal mode:
 * - Only DDG trackers with "block" action are blocked (not "ignore")
 * - Browsers and known incompatible apps are auto-excluded from VPN
 * - Hosts-file based blocking is disabled
 * - The "Content" category is never blocked (no strict_blocking)
 * - No granular per-tracker controls (only VPN include/exclude per app)
 */
public class BlockingMode {
    private static final String TAG = BlockingMode.class.getSimpleName();
    public static final String PREF_BLOCKING_MODE = "blocking_mode";
    public static final String MODE_MINIMAL = "minimal";
    public static final String MODE_STANDARD = "standard";
    public static final String MODE_STRICT = "strict";

    private static Set<String> excludedApps;

    /**
     * Get the current blocking mode string.
     */
    public static String getMode(Context c) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(c);
        return prefs.getString(PREF_BLOCKING_MODE, getDefaultMode());
    }

    /**
     * Check if the app is in DDG minimal blocking mode.
     */
    public static boolean isMinimalMode(Context c) {
        return MODE_MINIMAL.equals(getMode(c));
    }

    /**
     * Check if the app is in strict blocking mode.
     */
    public static boolean isStrictMode(Context c) {
        return MODE_STRICT.equals(getMode(c));
    }

    /**
     * Get the default blocking mode for new users.
     * Minimal is the safest default — least app breakage.
     */
    public static String getDefaultMode() {
        return MODE_MINIMAL;
    }

    /**
     * Get the set of package names that should be excluded from VPN in minimal mode.
     * This includes browsers and known incompatible apps from DDG's list.
     */
    public static Set<String> getExcludedApps(Context c) {
        if (excludedApps != null)
            return excludedApps;

        excludedApps = loadExcludedApps(c);
        return excludedApps;
    }

    /**
     * Check if a specific app should be excluded in minimal mode.
     */
    public static boolean isAppExcludedInMinimalMode(Context c, String packageName) {
        if (!isMinimalMode(c))
            return false;
        return getExcludedApps(c).contains(packageName);
    }

    /**
     * Load excluded apps from the DDG excluded apps JSON asset.
     */
    private static Set<String> loadExcludedApps(Context c) {
        Set<String> apps = new HashSet<>();
        try (InputStream is = c.getAssets().open("ddg-excluded-apps.json")) {
            int size = is.available();
            byte[] buffer = new byte[size];
            if (is.read(buffer) <= 0)
                throw new IOException("No bytes read.");

            String json = new String(buffer, StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(json);

            // Load all categories
            String[] categories = {"browsers", "system_ims", "vpn_incompatible", "user_reported"};
            for (String category : categories) {
                if (root.has(category)) {
                    JSONArray arr = root.getJSONArray(category);
                    for (int i = 0; i < arr.length(); i++) {
                        apps.add(arr.getString(i));
                    }
                }
            }

            Log.i(TAG, "Loaded " + apps.size() + " excluded apps for minimal mode");
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Failed to load excluded apps", e);
        }
        return Collections.unmodifiableSet(apps);
    }

    /**
     * Apply minimal mode exclusions to the apply SharedPreferences.
     * Called when switching to minimal mode or on first run with minimal mode default.
     */
    public static void applyMinimalModeExclusions(Context c) {
        if (!isMinimalMode(c))
            return;

        Set<String> excluded = getExcludedApps(c);
        SharedPreferences apply = c.getSharedPreferences("apply", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = apply.edit();

        for (String packageName : excluded) {
            // Only set to false if not already explicitly configured by user
            if (!apply.contains(packageName)) {
                editor.putBoolean(packageName, false);
            }
        }
        editor.apply();

        Log.i(TAG, "Applied minimal mode VPN exclusions");
    }

    /**
     * Clear invalidated excluded apps cache (e.g. when config changes).
     */
    public static void invalidateCache() {
        excludedApps = null;
    }
}
