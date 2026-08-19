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
     * Persist the current blocklist.
     * <p>
     * The list is otherwise only written when the details screen is paused, so
     * a change made from the main screen — or made shortly before the process
     * dies — would be lost. Both UI paths write through
     * {@link #block(Context, int)} / {@link #unblock(Context, int)} instead.
     *
     * @param c Context
     */
    public synchronized void saveSettings(Context c) {
        SharedPreferences prefs = c.getSharedPreferences(PREF_BLOCKLIST, Context.MODE_PRIVATE);
        Set<String> set = new HashSet<>();
        for (Integer uid : blockmap)
            set.add(Integer.toString(uid));

        prefs.edit().putStringSet(SHARED_PREFS_INTERNET_BLOCKLIST_APPS_KEY, set).apply();
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
     * Block internet for a given app and persist the change immediately.
     *
     * @param c   Context
     * @param uid Uid of app to block internet
     */
    public synchronized void block(Context c, int uid) {
        block(uid);
        saveSettings(c);
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
     * Unblock internet for a given app and persist the change immediately.
     *
     * @param c   Context
     * @param uid Uid of app to unblock internet
     */
    public synchronized void unblock(Context c, int uid) {
        unblock(uid);
        saveSettings(c);
    }

    /**
     * Apply a resolved protection state's internet decision, if it has one.
     *
     * @param c              Context
     * @param uid            Uid of the app
     * @param internetBlocked the {@code internetBlocked} field of an
     *                       {@link AppProtectionState.Change}; {@code null}
     *                       leaves the blocklist untouched
     */
    public void apply(Context c, int uid, Boolean internetBlocked) {
        if (internetBlocked == null)
            return;

        if (internetBlocked)
            block(c, uid);
        else
            unblock(c, uid);
    }

    /**
     * Check if internet is blocked for given app
     *
     * @param uid Uid of app to check
     * @return If internet is blocked for given app
     */
    public synchronized boolean blockedInternet(int uid) {
        return blockmap.contains(uid);
    }
}
