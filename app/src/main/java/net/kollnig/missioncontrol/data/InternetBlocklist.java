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

import java.util.HashSet;
import java.util.Set;

import static net.kollnig.missioncontrol.data.TrackerBlocklist.PREF_BLOCKLIST;

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
     * Singleton getter.
     *
     * @param c context used to access shared preferences from.
     * @return The current instance of the InternetBlocklist, if none, a new instance is created.
     */
    public static InternetBlocklist getInstance(Context c) {
        if (instance == null)
            instance = new InternetBlocklist(c);
        return instance;
    }

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

    public Set<Integer> getBlocklist() {
        return blockmap;
    }

    public void clear() {
        blockmap.clear();
    }

    public synchronized void block(int uid) {
        blockmap.add(uid);
    }

    public synchronized void unblock(int uid) {
        blockmap.remove(uid);
    }

    public boolean blockedInternet(int uid) {
        return blockmap.contains(uid);
    }
}
