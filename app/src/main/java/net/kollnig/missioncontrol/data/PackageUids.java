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
 * Copyright © 2019–2026 Konrad Kollnig
 */
package net.kollnig.missioncontrol.data;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

/**
 * The one place that turns a package name into an Android UID, and that asks a
 * UID whether it still has any package left.
 * <p>
 * Both questions have the same two failure modes, and the two must not be
 * conflated: {@link PackageManager.NameNotFoundException} means <em>absent</em>,
 * while a {@link SecurityException} — another user or work profile on Android
 * 16+ — means <em>unknown</em>. Callers that clear state on "absent" would
 * silently wipe a still-installed app's settings if they read "unknown" the
 * same way, so the distinction is kept at the seam rather than re-derived by
 * each caller.
 */
public final class PackageUids {
    private static final String TAG = "TrackerControl.PackageUids";

    private PackageUids() {
    }

    /**
     * Resolves a stored package name to a runtime UID.
     * <p>
     * A seam so callers can be tested without a {@link PackageManager}.
     */
    public interface Resolver {
        /**
         * @param packageName Package name to resolve
         * @return The package's UID, or {@code null} when it cannot be resolved
         */
        Integer resolve(String packageName);
    }

    /**
     * Reports which packages, if any, still share a UID.
     * <p>
     * A seam so callers can be tested without a {@link PackageManager}. Unlike
     * {@link Resolver} this deliberately lets a {@link SecurityException}
     * escape: "unknown" and "none left" must stay distinguishable.
     */
    public interface Lookup {
        String[] getPackagesForUid(int uid);
    }

    /**
     * Resolve a package name to its UID.
     *
     * @param c           Context
     * @param packageName Package name to resolve
     * @return The package's UID, or {@code null} when the package is not
     * installed or its UID cannot be inspected. Callers keep such an entry
     * pending rather than dropping it.
     */
    public static Integer resolve(Context c, String packageName) {
        if (packageName == null || packageName.length() == 0)
            return null;

        try {
            return c.getPackageManager().getApplicationInfo(packageName, 0).uid;
        } catch (PackageManager.NameNotFoundException ignored) {
            // Not installed (yet).
            return null;
        } catch (SecurityException ex) {
            Log.w(TAG, "Cannot resolve " + packageName + ": " + ex.getMessage());
            return null;
        }
    }

    /**
     * @param c Context
     * @return A {@link Resolver} backed by the platform {@link PackageManager}
     */
    public static Resolver resolver(final Context c) {
        return new Resolver() {
            @Override
            public Integer resolve(String packageName) {
                return PackageUids.resolve(c, packageName);
            }
        };
    }

    /**
     * @param c Context
     * @return A {@link Lookup} backed by the platform {@link PackageManager}
     */
    public static Lookup lookup(final Context c) {
        return new Lookup() {
            @Override
            public String[] getPackagesForUid(int uid) {
                return c.getPackageManager().getPackagesForUid(uid);
            }
        };
    }
}
