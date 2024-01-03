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
import android.content.pm.PackageManager;

import org.apache.commons.lang3.StringUtils;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores what trackers are blocked, for each app.
 */
public class TrackerBlocklist {
    public static final String SHARED_PREFS_BLOCKLIST_APPS_KEY = "APPS_BLOCKLIST_APPS_KEY";
    final public static String PREF_BLOCKLIST = "blocklist";
    public static String NECESSARY_CATEGORY = "Content";
    private static TrackerBlocklist instance;
    /**
     * Whilst blockmap is a list of apps to block, the set is a set of trackers not to block.
     */
    private final Map<Integer, Set<String>> blockmap = new ConcurrentHashMap<>();

    private TrackerBlocklist(Context c) {
        // Initialize Concurrent Set using values from shared preferences if possible.
        if (c != null)
            loadSettings(c);
    }

    /**
     * Singleton getter for TrackerBlocklist
     *
     * @param c context used to access TrackerBlocklist from
     * @return The current instance of the TrackerBlocklist, if none, a new instance is created.
     */
    public static TrackerBlocklist getInstance(Context c) {
        if (instance == null)
            instance = new TrackerBlocklist(c);

        return instance;
    }

    /**
     * For a given tracker company, this computes a key to store the blocking state of this tracker.
     *
     * @param t Tracker company
     * @return The key for storage of the blocking state
     */
    public static String getBlockingKey(Tracker t) {
        return t.category + " | " + t.getName();
    }

    /**
     * Load past settings
     *
     * @param c Context
     */
    public void loadSettings(Context c) {
        SharedPreferences prefs = c.getSharedPreferences(PREF_BLOCKLIST, Context.MODE_PRIVATE);
        Set<String> set = prefs.getStringSet(SHARED_PREFS_BLOCKLIST_APPS_KEY, null);

        if (set != null) {
            blockmap.clear();
            for (String appUid : set) {
                // Get saved blocklist for UID
                Set<String> prefset = prefs.getStringSet
                        (SHARED_PREFS_BLOCKLIST_APPS_KEY + "_" + appUid, null);

                // Make an editable copy
                Set<String> subset = new HashSet<>();
                if (prefset != null)
                    subset.addAll(prefset);

                // Migrate from older TC version
                if (subset.contains("Uncategorised | Alphabet")) {
                    subset.remove("Uncategorised | Alphabet");
                    subset.add("Uncategorised | Google");
                }
                if (subset.contains("Uncategorised | Adobe Systems")) {
                    subset.remove("Uncategorised | Adobe Systems");
                    subset.add("Uncategorised | Adobe");
                }
                if (subset.contains("FingerprintingGeneral")) {
                    subset.remove("FingerprintingGeneral");
                    subset.add("Fingerprinting");
                }
                if (subset.contains("FingerprintingInvasive")) {
                    subset.remove("FingerprintingInvasive");
                    subset.add("Fingerprinting");
                }
                if (subset.contains("EmailStrict")) {
                    subset.remove("EmailStrict");
                    subset.add("Email");
                }
                if (subset.contains("EmailAggressive")) {
                    subset.remove("EmailAggressive");
                    subset.add("Email");
                }

                // Retrieve uid
                int uid = -1;
                if (StringUtils.isNumeric(appUid))
                    uid = Integer.parseInt(appUid);
                else {
                    // Convert from old TrackerControl version
                    try {
                        uid = c.getPackageManager().getApplicationInfo(appUid, 0).uid;
                    } catch (PackageManager.NameNotFoundException e) {
                        e.printStackTrace();
                    }
                }

                if (uid >= 0)
                    blockmap.put(uid, subset);
            }
        }
    }

    /**
     * Get set of apps' uids which have information about blocked trackers
     *
     * @return Set of apps' uids
     */
    public Set<Integer> getBlocklist() {
        return blockmap.keySet();
    }

    /**
     * Get information about what specific trackers are blocked for a given app
     *
     * @param uid Uid of the app
     * @return Information about what specific trackers are blocked
     */
    public Set<String> getSubset(int uid) {
        return blockmap.get(uid);
    }

    /**
     * Completely clear blocklist.
     */
    public void clear() {
        blockmap.clear();
    }

    /**
     * Clear all blocked trackers for a specific app
     *
     * @param uid Uid of app
     */
    public void clear(int uid) {
        blockmap.remove(uid);
    }

    /**
     * Block a given tracker for a given app
     *
     * @param uid Uid of the app
     * @param t   Key of the tracker to be blocked
     */
    public synchronized void block(int uid, String t) {
        Set<String> app = blockmap.get(uid);
        if (app == null)
            return;
        app.remove(t);
    }

    /**
     * Unlock a given tracker for a given app
     *
     * @param uid Uid of the app
     * @param t   Key of the tracker to be unblocked
     */
    public synchronized void unblock(int uid, String t) {
        Set<String> app = blockmap.get(uid);

        if (app == null) {
            app = new HashSet<>();
            blockmap.put(uid, app);
        }

        app.add(t);
    }

    /**
     * Block a given tracker for a given app
     *
     * @param uid Uid of the app
     * @param t   Tracker to be blocked
     */
    public synchronized void block(int uid, Tracker t) {
        block(uid, getBlockingKey(t));
    }

    /**
     * Unblock a given tracker for a given app
     *
     * @param uid Uid of the app
     * @param t   Tracker to be unblocked
     */
    public synchronized void unblock(int uid, Tracker t) {
        unblock(uid, getBlockingKey(t));
    }

    /**
     * Check if a given app can access a given tracker
     *
     * @param uid Uid of the app
     * @param key Key of the tracker
     * @return Whether access to this tracker is blocked
     */
    public boolean blocked(int uid, String key) {
        Set<String> trackers = this.getSubset(uid);
        if (trackers == null) {
            return true;
        }

        return !trackers.contains(key); // negate since it's a whitelist
    }

    /**
     * Check if a given app can access a given tracker
     *
     * @param uid Uid of the app
     * @param t   Tracker
     * @return Whether access to this tracker is blocked
     */
    public boolean blockedTracker(int uid, Tracker t) {
        return blocked(uid, t.category)
                && blocked(uid, getBlockingKey(t));
    }
}
