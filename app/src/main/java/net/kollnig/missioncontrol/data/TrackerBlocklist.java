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
        if (c != null) {
            loadSettings(c);
        }
    }

    /**
     * Singleton getter.
     *
     * @param c context used to access shared preferences from.
     * @return The current instance of the TrackerBlocklist, if none, a new instance is created.
     */
    public static TrackerBlocklist getInstance(Context c) {
        if (instance == null)
            instance = new TrackerBlocklist(c);
        return instance;
    }

    public static String getBlockingKey(Tracker t) {
        return t.category + " | " + t.getName();
    }

    public void loadSettings(Context c) {
        SharedPreferences prefs = c.getSharedPreferences(PREF_BLOCKLIST, Context.MODE_PRIVATE);
        Set<String> set = prefs.getStringSet(SHARED_PREFS_BLOCKLIST_APPS_KEY, null);

        if (set != null) {
            blockmap.clear();
            for (String appUid : set) {
                // Get saved blocklist for UID
                Set<String> prefset = prefs.getStringSet
                        (SHARED_PREFS_BLOCKLIST_APPS_KEY + "_" + appUid, null);
                Set<String> subset = new HashSet<>(); // make an editable copy
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

                // Retrieve uid
                int uid = -1;
                if (StringUtils.isNumeric(appUid)) {
                    uid = Integer.parseInt(appUid);
                } else {
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

    public Set<Integer> getBlocklist() {
        return blockmap.keySet();
    }

    public Set<String> getSubset(int uid) {
        return blockmap.get(uid);
    }

    public void clear() {
        blockmap.clear();
    }

    public void clear(int uid) {
        blockmap.remove(uid);
    }

    public synchronized void block(int uid, String t) {
        Set<String> app = blockmap.get(uid);
        if (app == null)
            return;
        app.remove(t);
    }

    public synchronized void unblock(int uid, String t) {
        Set<String> app = blockmap.get(uid);

        if (app == null) {
            app = new HashSet<>();
            blockmap.put(uid, app);
        }

        app.add(t);
    }

    public synchronized void block(int uid, Tracker t) {
        block(uid, getBlockingKey(t));
    }

    public synchronized void unblock(int uid, Tracker t) {
        unblock(uid, getBlockingKey(t));
    }

    public boolean blocked(int uid, String key) {
        Set<String> trackers = this.getSubset(uid);
        if (trackers == null) {
            return true;
        }

        return !trackers.contains(key); // negate since it's a whitelist
    }

    public boolean blockedTracker(int uid, Tracker t) {
        return blocked(uid, t.category)
                && blocked(uid, getBlockingKey(t));
    }
}
