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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import eu.faircode.netguard.ServiceSinkhole;
import eu.faircode.netguard.Util;

/**
 * Manages the DDG Minimal Blocking mode.
 *
 * In minimal mode:
 * - Only DDG trackers with "block" action are blocked (not "ignore")
 * - Browsers stay routed through the VPN, but tracker protection defaults off
 * - Known VPN-incompatible apps are auto-excluded from VPN
 * - Hosts-file based blocking is disabled
 * - The "Content" category is never blocked (no strict_blocking)
 * - No granular per-tracker controls
 */
public class BlockingMode {
    private static final String TAG = BlockingMode.class.getSimpleName();
    public static final String PREF_BLOCKING_MODE = "blocking_mode";
    private static final String PREF_MINIMAL_AUTO_EXCLUDED_APPS = "minimal_auto_excluded_apps";
    public static final String MODE_MINIMAL = BlockingModeLogic.MODE_MINIMAL;
    public static final String MODE_STANDARD = BlockingModeLogic.MODE_STANDARD;
    public static final String MODE_STRICT = BlockingModeLogic.MODE_STRICT;

    private static Set<String> excludedApps;
    private static Set<String> browserApps;

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
     * Minimal mode normally forces tracker protection on for apps that are in
     * the VPN route. Browsers are the exception: they stay routed for VPN /
     * WireGuard privacy, but app-level tracker blocking defaults off because
     * browser-native blocking is usually more precise.
     */
    public static boolean isTrackerProtectionEnabled(Context c,
            SharedPreferences trackerProtectPrefs,
            String packageName) {
        if (isBrowserApp(c, packageName))
            return trackerProtectPrefs.getBoolean(packageName, false);
        return isMinimalMode(c) || trackerProtectPrefs.getBoolean(packageName, true);
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
     * Browser packages are deliberately excluded from this set; browser
     * compatibility is handled by disabling tracker protection by default, not
     * by bypassing the VPN route.
     */
    public static Set<String> getExcludedApps(Context c) {
        if (excludedApps == null)
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
    public static boolean isBrowserApp(Context c, String packageName) {
        if (browserApps == null)
            browserApps = loadBrowserApps(c);
        return browserApps.contains(packageName);
    }

    private static Set<String> loadExcludedApps(Context c) {
        Set<String> apps = new HashSet<>();
        try (InputStream is = c.getAssets().open("ddg-excluded-apps.json")) {
            int size = is.available();
            byte[] buffer = new byte[size];
            if (is.read(buffer) <= 0)
                throw new IOException("No bytes read.");

            String json = new String(buffer, StandardCharsets.UTF_8);
            apps.addAll(BlockingModeLogic.parseExcludedAppsJson(json));

            Log.i(TAG, "Loaded " + apps.size() + " excluded apps for minimal mode");
        } catch (IOException e) {
            Log.e(TAG, "Failed to load excluded apps", e);
        }
        return Collections.unmodifiableSet(apps);
    }

    private static Set<String> loadBrowserApps(Context c) {
        Set<String> apps = new HashSet<>();
        try (InputStream is = c.getAssets().open("ddg-excluded-apps.json")) {
            int size = is.available();
            byte[] buffer = new byte[size];
            if (is.read(buffer) <= 0)
                throw new IOException("No bytes read.");

            String json = new String(buffer, StandardCharsets.UTF_8);
            apps.addAll(BlockingModeLogic.parseBrowserAppsJson(json));

            Log.i(TAG, "Loaded " + apps.size() + " browser apps");
        } catch (IOException e) {
            Log.e(TAG, "Failed to load browser apps", e);
        }
        return Collections.unmodifiableSet(apps);
    }

    /**
     * Synchronize auto-managed VPN exclusions with the selected blocking mode.
     * Minimal mode auto-excludes known incompatible apps; standard/strict restore
     * any exclusions that were added automatically when minimal mode was active.
     */
    public static void syncModeExclusions(Context c) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(c);
        SharedPreferences apply = c.getSharedPreferences("apply", Context.MODE_PRIVATE);
        BlockingModeLogic.ExclusionSyncResult result = BlockingModeLogic.syncVpnExclusions(
                getMode(c),
                getExcludedApps(c),
                getBooleanPrefs(apply),
                getAutoExcludedApps(prefs));

        if (!result.applyFalsePackages.isEmpty() || !result.applyRemovals.isEmpty()) {
            SharedPreferences.Editor applyEditor = apply.edit();
            for (String packageName : result.applyRemovals)
                applyEditor.remove(packageName);
            for (String packageName : result.applyFalsePackages)
                applyEditor.putBoolean(packageName, false);
            applyEditor.apply();
        }

        SharedPreferences.Editor prefsEditor = prefs.edit();
        if (result.autoExcludedApps.isEmpty())
            prefsEditor.remove(PREF_MINIMAL_AUTO_EXCLUDED_APPS);
        else
            prefsEditor.putStringSet(PREF_MINIMAL_AUTO_EXCLUDED_APPS, result.autoExcludedApps);
        prefsEditor.apply();

        Log.i(TAG, (isMinimalMode(c) ? "Applied" : "Restored") + " mode-managed VPN exclusions");
    }

    /**
     * Apply all runtime side effects of the current blocking mode.
     */
    public static void applyMode(Context c) {
        syncModeExclusions(c);

        TrackerBlocklist trackerBlocklist = TrackerBlocklist.getInstance(c);
        if (trackerBlocklist.applyStrictModeToAll(isStrictMode(c)))
            trackerBlocklist.saveSettings(c);

        ServiceSinkhole.clearTrackerCaches();
        TrackerList.reloadTrackerData(c);
        ServiceSinkhole.reload("changed " + PREF_BLOCKING_MODE, c, false);
    }

    /**
     * Backwards-compatible entry point for callers that only knew about applying
     * minimal mode exclusions.
     */
    public static void applyMinimalModeExclusions(Context c) {
        syncModeExclusions(c);
    }

    public static void clearAutoExcludedApp(Context c, String packageName) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(c);
        Set<String> autoExcludedApps = getAutoExcludedApps(prefs);
        Set<String> updatedAutoExcludedApps = BlockingModeLogic.clearAutoExcludedApp(autoExcludedApps, packageName);
        if (autoExcludedApps.equals(updatedAutoExcludedApps))
            return;

        SharedPreferences.Editor editor = prefs.edit();
        if (updatedAutoExcludedApps.isEmpty())
            editor.remove(PREF_MINIMAL_AUTO_EXCLUDED_APPS);
        else
            editor.putStringSet(PREF_MINIMAL_AUTO_EXCLUDED_APPS, updatedAutoExcludedApps);
        editor.apply();
    }

    /**
     * Clear invalidated excluded apps cache (e.g. when config changes).
     */
    public static void invalidateCache() {
        excludedApps = null;
        browserApps = null;
    }

    private static Set<String> getAutoExcludedApps(SharedPreferences prefs) {
        return new HashSet<>(prefs.getStringSet(PREF_MINIMAL_AUTO_EXCLUDED_APPS, Collections.emptySet()));
    }

    private static Map<String, Boolean> getBooleanPrefs(SharedPreferences prefs) {
        Map<String, Boolean> values = new HashMap<>();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet())
            if (entry.getValue() instanceof Boolean)
                values.put(entry.getKey(), (Boolean) entry.getValue());
        return values;
    }
}
