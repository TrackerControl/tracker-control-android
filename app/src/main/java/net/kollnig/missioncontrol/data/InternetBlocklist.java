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
import android.content.pm.PackageManager;

import java.util.HashSet;
import java.util.Iterator;
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
    private final Set<String> rawBlockmap = new HashSet<>();

    interface PackageUidResolver {
        Integer resolve(String packageName);
    }

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
    public static synchronized InternetBlocklist getInstance(Context c) {
        if (instance == null)
            instance = new InternetBlocklist(c);
        return instance;
    }

    /**
     * Load past settings
     *
     * @param c Context
     */
    public synchronized void loadSettings(Context c) {
        SharedPreferences prefs = c.getSharedPreferences(PREF_BLOCKLIST, Context.MODE_PRIVATE);
        Set<String> set = prefs.getStringSet(SHARED_PREFS_INTERNET_BLOCKLIST_APPS_KEY, null);

        PackageUidResolver resolver = new PackageUidResolver() {
            @Override
            public Integer resolve(String packageName) {
                try {
                    return c.getPackageManager().getApplicationInfo(packageName, 0).uid;
                } catch (PackageManager.NameNotFoundException ignored) {
                    return null;
                } catch (SecurityException ignored) {
                    return null;
                }
            }
        };

        blockmap.clear();
        rawBlockmap.clear();
        if (set != null) {
            // Numeric entries are canonical. Keep unresolved package names so
            // they can be resolved when the app is installed later.
            for (String id : set) {
                Integer uid = resolveStoredUid(id, resolver);
                if (uid == null)
                    rawBlockmap.add(id);
                else
                    blockmap.add(uid);
            }
        }
    }

    private static Integer resolveStoredUid(String storedUid, PackageUidResolver resolver) {
        if (storedUid == null || storedUid.length() == 0)
            return null;

        try {
            return Integer.parseInt(storedUid);
        } catch (NumberFormatException ignored) {
            // Legacy exports may contain package names. Resolve those below.
        }

        return resolver == null ? null : resolver.resolve(storedUid);
    }

    /**
     * Resolve package-name entries which were unavailable when settings were loaded.
     *
     * @param c Context
     * @return Whether any pending entry was resolved
     */
    public synchronized boolean resolvePendingPackages(Context c) {
        PackageUidResolver resolver = new PackageUidResolver() {
            @Override
            public Integer resolve(String packageName) {
                try {
                    return c.getPackageManager().getApplicationInfo(packageName, 0).uid;
                } catch (PackageManager.NameNotFoundException ignored) {
                    return null;
                } catch (SecurityException ignored) {
                    return null;
                }
            }
        };

        return resolvePendingPackages(resolver);
    }

    synchronized boolean resolvePendingPackages(PackageUidResolver resolver) {
        boolean changed = false;
        Iterator<String> pending = rawBlockmap.iterator();
        while (pending.hasNext()) {
            Integer uid = resolveStoredUid(pending.next(), resolver);
            if (uid != null) {
                blockmap.add(uid);
                pending.remove();
                changed = true;
            }
        }
        return changed;
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
        rawBlockmap.clear();
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
        set.addAll(rawBlockmap);

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
