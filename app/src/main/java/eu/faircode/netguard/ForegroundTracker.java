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
package eu.faircode.netguard;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which apps are currently in the foreground.
 * Uses UsageStatsManager to monitor app lifecycle events.
 */
public class ForegroundTracker {
    private static final String TAG = "TrackerControl.Foreground";
    private static ForegroundTracker instance;
    private final Context context;
    private final ConcurrentHashMap<Integer, Boolean> foregroundApps = new ConcurrentHashMap<>();
    private long lastCheckTime = 0;

    private ForegroundTracker(Context c) {
        this.context = c.getApplicationContext();
    }

    /**
     * Get the singleton instance of ForegroundTracker
     *
     * @param c context used to access system services
     * @return The current instance of ForegroundTracker
     */
    public static synchronized ForegroundTracker getInstance(Context c) {
        if (instance == null)
            instance = new ForegroundTracker(c);
        return instance;
    }

    /**
     * Check if an app with the given UID is currently in the foreground.
     *
     * @param uid UID of the app to check
     * @return true if the app is in foreground, false otherwise
     */
    public boolean isAppInForeground(int uid) {
        updateForegroundApps();
        Boolean isForeground = foregroundApps.get(uid);
        return isForeground != null && isForeground;
    }

    /**
     * Update the list of foreground apps by querying UsageStatsManager.
     * This is throttled to avoid excessive system calls.
     */
    private void updateForegroundApps() {
        long currentTime = System.currentTimeMillis();
        
        // Throttle updates to once per 500ms to reduce overhead
        if (currentTime - lastCheckTime < 500) {
            return;
        }
        
        lastCheckTime = currentTime;

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
            // UsageStatsManager not available on older versions
            return;
        }

        try {
            UsageStatsManager usageStatsManager = (UsageStatsManager) 
                context.getSystemService(Context.USAGE_STATS_SERVICE);
            
            if (usageStatsManager == null) {
                return;
            }

            // Query events from the last 2 seconds
            long startTime = currentTime - 2000;
            UsageEvents usageEvents = usageStatsManager.queryEvents(startTime, currentTime);

            // Track the latest state for each UID
            Set<Integer> newForegroundApps = new HashSet<>();
            
            // Start with existing state
            for (Integer uid : foregroundApps.keySet()) {
                if (foregroundApps.get(uid)) {
                    newForegroundApps.add(uid);
                }
            }

            // Process usage events to update state
            while (usageEvents.hasMoreEvents()) {
                UsageEvents.Event event = new UsageEvents.Event();
                usageEvents.getNextEvent(event);

                String packageName = event.getPackageName();
                int eventType = event.getEventType();

                try {
                    int uid = context.getPackageManager()
                        .getApplicationInfo(packageName, 0).uid;

                    if (eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                        eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                        newForegroundApps.add(uid);
                    } else if (eventType == UsageEvents.Event.ACTIVITY_PAUSED ||
                               eventType == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                        newForegroundApps.remove(uid);
                    }
                } catch (PackageManager.NameNotFoundException e) {
                    // Package not found, skip
                }
            }

            // Update the concurrent map
            foregroundApps.clear();
            for (Integer uid : newForegroundApps) {
                foregroundApps.put(uid, true);
            }

        } catch (SecurityException e) {
            Log.w(TAG, "No permission to access usage stats: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Error updating foreground apps: " + e.getMessage(), e);
        }
    }

    /**
     * Clear cached foreground state.
     */
    public void clear() {
        foregroundApps.clear();
        lastCheckTime = 0;
    }

    /**
     * Check if the app has permission to access usage stats.
     *
     * @param context Context
     * @return true if permission is granted
     */
    public static boolean hasUsageStatsPermission(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
            return false;
        }

        try {
            AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (appOps == null) {
                return false;
            }

            int mode;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mode = appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.getPackageName()
                );
            } else {
                mode = appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.getPackageName()
                );
            }

            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            Log.e(TAG, "Error checking usage stats permission: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * Open the system settings to request usage stats permission.
     *
     * @param context Context
     */
    public static void requestUsageStatsPermission(Context context) {
        Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
}
