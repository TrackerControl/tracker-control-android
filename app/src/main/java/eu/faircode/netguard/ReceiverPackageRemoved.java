/*
 * This file is from NetGuard.
 *
 * NetGuard is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NetGuard is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NetGuard.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright © 2015–2020 by Marcel Bokhorst (M66B), Konrad
 * Kollnig (University of Oxford)
 */

package eu.faircode.netguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.core.app.NotificationManagerCompat;

import net.kollnig.missioncontrol.data.InternetBlocklist;
import net.kollnig.missioncontrol.data.TrackerBlocklist;

public class ReceiverPackageRemoved extends BroadcastReceiver {
    private static final String TAG = "TrackerControl.Receiver";

    interface PackageUidLookup {
        String[] getPackagesForUid(int uid);
    }

    static boolean shouldClearUid(PackageUidLookup lookup, int uid) {
        // UID-keyed state may still be in use by another package of a shared UID.
        // Clear it only once the UID has no package left, otherwise a reinstall
        // silently comes back with the old app's settings.
        // A SecurityException (other user/profile on Android 16+)
        // means "unknown", which must not be read as "none left".
        try {
            String[] packages = lookup.getPackagesForUid(uid);
            return packages == null || packages.length == 0;
        } catch (SecurityException ex) {
            Log.w(TAG, "Keeping UID state for uid " + uid + ": " + ex.getMessage());
            return false;
        }
    }

    static void clearUidState(Context context, int uid, PackageUidLookup lookup) {
        if (!shouldClearUid(lookup, uid))
            return;

        DatabaseHelper dh = DatabaseHelper.getInstance(context);
        dh.clearLog(uid);
        dh.clearAccess(uid, false);

        InternetBlocklist internetBlocklist = InternetBlocklist.getInstance(context);
        if (internetBlocklist.blockedInternet(uid))
            internetBlocklist.unblock(context, uid);

        TrackerBlocklist trackerBlocklist = TrackerBlocklist.getInstance(context);
        trackerBlocklist.clear(uid);
        trackerBlocklist.saveSettings(context);

        NotificationManagerCompat.from(context).cancel(uid); // installed notification
        NotificationManagerCompat.from(context).cancel(uid + 10000); // access notification
    }

    @Override
    public void onReceive(final Context context, Intent intent) {
        Log.i(TAG, "Received " + intent);
        Util.logExtras(intent);

        String action = (intent == null ? null : intent.getAction());
        if (Intent.ACTION_PACKAGE_FULLY_REMOVED.equals(action)) {
            int uid = intent.getIntExtra(Intent.EXTRA_UID, 0);
            if (uid > 0) {
                clearUidState(context, uid, new PackageUidLookup() {
                    @Override
                    public String[] getPackagesForUid(int uid) {
                        return context.getPackageManager().getPackagesForUid(uid);
                    }
                });
            }
        }
    }
}
