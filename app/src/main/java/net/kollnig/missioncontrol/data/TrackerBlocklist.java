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

import org.apache.commons.lang3.StringUtils;

import java.util.HashSet;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stores what trackers are blocked, for each app.
 */
public class TrackerBlocklist extends UidKeyedStore<Set<String>> {
    public static final String SHARED_PREFS_BLOCKLIST_APPS_KEY = "APPS_BLOCKLIST_APPS_KEY";
    final public static String PREF_BLOCKLIST = "blocklist";
    public static String NECESSARY_CATEGORY = "Content";
    private static TrackerBlocklist instance;

    private TrackerBlocklist(Context c) {
        // Initialize Concurrent Set using values from shared preferences if possible.
        if (c != null)
            loadSettings(c);
    }

    /**
     * Singleton getter for TrackerBlocklist
     *
     * @param c context used to access TrackerBlocklist from
     * @return The current instance of the TrackerBlocklist, if none, a new instance
     *         is created.
     */
    // Called from both native packet threads and UI threads.
    public static synchronized TrackerBlocklist getInstance(Context c) {
        if (instance == null)
            instance = new TrackerBlocklist(c);

        return instance;
    }

    static synchronized void resetForTests() {
        instance = null;
    }

    /**
     * For a given tracker company, this computes a key to store the blocking state
     * of this tracker.
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
    public synchronized void loadSettings(Context c) {
        SharedPreferences prefs = c.getSharedPreferences(PREF_BLOCKLIST, Context.MODE_PRIVATE);
        Set<String> set = prefs.getStringSet(SHARED_PREFS_BLOCKLIST_APPS_KEY, null);
        PackageUids.Resolver resolver = PackageUids.resolver(c);

        clear();
        if (set != null) {
            List<String> storedIds = new ArrayList<>(set);
            Collections.sort(storedIds);

            // Numeric entries are canonical. This makes a collision with a
            // legacy package-name entry deterministic rather than dependent on
            // SharedPreferences' StringSet iteration order.
            for (String appUid : storedIds) {
                if (!StringUtils.isNumeric(appUid))
                    continue;

                Integer uid = resolveStoredUid(appUid, null);
                if (uid != null)
                    blockmap.put(uid, loadSubset(prefs, appUid));
                else
                    // Numeric, but too large to be a UID. Keep the entry as
                    // written rather than dropping settings we cannot parse.
                    rawBlockmap.put(appUid, loadRawSubset(prefs, appUid));
            }

            for (String appUid : storedIds) {
                if (StringUtils.isNumeric(appUid))
                    continue;

                // Get saved blocklist for UID
                Set<String> rawSubset = loadRawSubset(prefs, appUid);

                // Retrieve uid
                Integer uid = resolveStoredUid(appUid, resolver);

                if (uid == null) {
                    rawBlockmap.put(appUid, rawSubset);
                } else if (blockmap.containsKey(uid)) {
                    // A numeric entry wins. Retain the legacy entry under its
                    // original key so its settings are not silently lost.
                    rawBlockmap.put(appUid, rawSubset);
                    retainedRawUids.put(appUid, uid);
                } else {
                    blockmap.put(uid, migrateSubset(rawSubset));
                }
            }
        }
    }

    private Set<String> loadRawSubset(SharedPreferences prefs, String appUid) {
        Set<String> prefset = prefs.getStringSet(SHARED_PREFS_BLOCKLIST_APPS_KEY + "_" + appUid, null);
        return prefset == null ? null : new HashSet<>(prefset);
    }

    private Set<String> loadSubset(SharedPreferences prefs, String appUid) {
        return migrateSubset(loadRawSubset(prefs, appUid));
    }

    private Set<String> migrateSubset(Set<String> rawSubset) {
        // Make an editable copy
        Set<String> subset = rawSubset == null ? new HashSet<>() : new HashSet<>(rawSubset);

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
        return subset;
    }

    @Override
    protected Resolution absorb(int uid, String rawKey, Set<String> raw) {
        if (!blockmap.containsKey(uid)) {
            blockmap.put(uid, migrateSubset(raw));
            return Resolution.ABSORBED;
        }

        // A numeric entry remains canonical if a package-name entry resolves to
        // the same UID. The entry stays pending, so report the collision only
        // the first time: otherwise every later call would claim a change and
        // force a needless save.
        return retainedRawUids.put(rawKey, uid) == null
                ? Resolution.RETAINED : Resolution.UNCHANGED;
    }

    public synchronized boolean hasSubset(int uid) {
        return blockmap.containsKey(uid);
    }

    public synchronized boolean ensureDefaults(int uid, boolean strictBlocking) {
        if (blockmap.containsKey(uid))
            return false;

        Set<String> subset = new HashSet<>();
        if (!strictBlocking)
            subset.add(NECESSARY_CATEGORY);
        blockmap.put(uid, subset);
        return true;
    }

    /**
     * Update all existing apps' Content category whitelist based on blocking mode.
     * In strict mode, remove Content from whitelist (block it).
     * In standard/minimal mode, add Content to whitelist (allow it).
     *
     * @return true if any changes were made
     */
    public synchronized boolean applyStrictModeToAll(boolean strictBlocking) {
        boolean changed = false;
        for (Map.Entry<Integer, Set<String>> entry : blockmap.entrySet()) {
            Set<String> subset = entry.getValue();
            if (strictBlocking) {
                changed |= subset.remove(NECESSARY_CATEGORY);
            } else {
                changed |= subset.add(NECESSARY_CATEGORY);
            }
        }
        return changed;
    }

    public synchronized void saveSettings(Context c) {
        SharedPreferences prefs = c.getSharedPreferences(PREF_BLOCKLIST, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        for (String key : prefs.getAll().keySet())
            if (SHARED_PREFS_BLOCKLIST_APPS_KEY.equals(key) ||
                    key.startsWith(SHARED_PREFS_BLOCKLIST_APPS_KEY + "_"))
                editor.remove(key);

        Set<String> trackerSet = new HashSet<>();
        for (Integer uid : blockmap.keySet())
            trackerSet.add(Integer.toString(uid));
        trackerSet.addAll(rawBlockmap.keySet());
        editor.putStringSet(SHARED_PREFS_BLOCKLIST_APPS_KEY, trackerSet);

        for (Map.Entry<Integer, Set<String>> entry : blockmap.entrySet())
            editor.putStringSet(SHARED_PREFS_BLOCKLIST_APPS_KEY + "_" + entry.getKey(), new HashSet<>(entry.getValue()));
        for (Map.Entry<String, Set<String>> entry : rawBlockmap.entrySet()) {
            if (entry.getValue() != null)
                editor.putStringSet(SHARED_PREFS_BLOCKLIST_APPS_KEY + "_" + entry.getKey(),
                        new HashSet<>(entry.getValue()));
        }

        editor.apply();
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
    public synchronized Set<String> getSubset(int uid) {
        return blockmap.get(uid);
    }

    /**
     * Clear all blocked trackers for a specific app
     *
     * @param uid Uid of app
     * @return Whether anything was cleared. {@link #saveSettings(Context)}
     * rewrites every key in the preferences file, so callers use this to skip
     * that work when the app had no state to begin with.
     */
    public synchronized boolean clear(int uid) {
        boolean changed = blockmap.remove(uid) != null;

        // Drop any legacy package-name entry that lost a collision to this uid:
        // the app is gone, so retaining it would resurrect stale settings if a
        // different app later took the same uid.
        Iterator<Map.Entry<String, Integer>> retained = retainedRawUids.entrySet().iterator();
        while (retained.hasNext()) {
            Map.Entry<String, Integer> entry = retained.next();
            if (entry.getValue() == uid) {
                rawBlockmap.remove(entry.getKey());
                retained.remove();
                changed = true;
            }
        }
        return changed;
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
    public synchronized boolean blocked(int uid, String key) {
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
    public synchronized boolean blockedTracker(int uid, Tracker t) {
        return blocked(uid, t.category)
                && blocked(uid, getBlockingKey(t));
    }
}
