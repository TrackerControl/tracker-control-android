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

import static net.kollnig.missioncontrol.data.TrackerBlocklist.PREF_BLOCKLIST;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/**
 * Stores those apps whose access to internet is blocked.
 * <p>
 * Analogous implementation to TrackerBlocklist.
 */
public class InternetBlocklist {
    public static final String SHARED_PREFS_INTERNET_BLOCKLIST_APPS_KEY = "INTERNET_BLOCKLIST_APPS_KEY";
    private static InternetBlocklist instance;
    private final HashSet<Integer> blockmap = new HashSet<>();

    private InternetBlocklist(Context c) {
        // Initialize Concurrent Set using values from shared preferences if possible.
        if (c != null) {
            loadSettings(c);
        }
    }

    /**
     * Singleton getter for InternetBlocklist
     *
     * @param c context used to access InternetBlocklist from
     * @return The current instance of the InternetBlocklist, if none, a new instance is created.
     */
    public static InternetBlocklist getInstance(Context c) {
        if (instance == null)
            instance = new InternetBlocklist(c);
        return instance;
    }

    /**
     * Load past settings
     *
     * @param c Context
     */
    public void loadSettings(Context c) {
        SharedPreferences prefs = c.getSharedPreferences(PREF_BLOCKLIST, Context.MODE_PRIVATE);
        Set<String> set = prefs.getStringSet(SHARED_PREFS_INTERNET_BLOCKLIST_APPS_KEY, null);

        if (set != null) {
            blockmap.clear();
            for (String id : set) {
                int uid = Integer.parseInt(id);
                blockmap.add(uid);
            }
        }
    }

    /**
     * Get set of apps' uids which shan't access internet
     *
     * @return Set of uids
     */
    public Set<Integer> getBlocklist() {
        return blockmap;
    }

    /**
     * Clear blocklist
     */
    public void clear() {
        blockmap.clear();
    }

    /**
     * Block internet for a given app
     *
     * @param uid Uid of app to block internet
     */
    public synchronized void block(int uid) {
        blockmap.add(uid);
    }

    /**
     * Unblock internet for a given app
     *
     * @param uid Uid of app to unblock internet
     */
    public synchronized void unblock(int uid) {
        blockmap.remove(uid);
    }

    /**
     * Check if internet is blocked for given app
     *
     * @param uid Uid of app to check
     * @return If internet is blocked for given app
     */
    public boolean blockedInternet(int uid) {
        return blockmap.contains(uid);
    }
}
