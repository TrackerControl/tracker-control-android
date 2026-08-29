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
 * Copyright © 2019–2021 Konrad Kollnig (University of Oxford)
 */

package net.kollnig.missioncontrol.analysis;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

/**
 * Manager class that handles caching and scheduling of tracker library analysis
 * via WorkManager.
 */
public class TrackerAnalysisManager {
    private static final String TAG = TrackerAnalysisManager.class.getSimpleName();
    private static final String PREFS_NAME = "library_analysis";
    private static final String WORK_NAME_PREFIX = "tracker_analysis_";
    private static final String ATTEMPTED_VERSION_PREFIX = "attempted_versioncode_";
    private static final String RESULT_PREFIX = "trackers_";
    private static final String VERSION_PREFIX = "versioncode_";

    private static TrackerAnalysisManager instance;
    private final Context mContext;
    private final WorkManager workManager;

    private TrackerAnalysisManager(Context context) {
        this.mContext = context.getApplicationContext();
        this.workManager = WorkManager.getInstance(mContext);

        // Trigger signature update in background
        new Thread(() -> {
            SharedPreferences prefs = getPrefs();
            long lastUpdate = prefs.getLong("last_signature_update", 0);
            if (System.currentTimeMillis() - lastUpdate >= 24 * 60 * 60 * 1000) {
                new TrackerSignatureManager(mContext).updateSignatures();
                long now = System.currentTimeMillis();
                prefs.edit().putLong("last_signature_update", now).apply();
            }
        }).start();
    }

    public static synchronized TrackerAnalysisManager getInstance(Context context) {
        if (instance == null) {
            instance = new TrackerAnalysisManager(context);
        }
        return instance;
    }

    /**
     * Starts an analysis for the given package using WorkManager.
     * Duplicate requests for the same package are ignored while one is pending.
     * Observe progress via {@link #getWorkInfoByPackageLiveData(String)}.
     *
     * @param packageName The package to analyze
     */
    public void startAnalysis(String packageName) {
        startAnalysis(packageName, -1, null);
    }

    /**
     * Starts an analysis and optionally updates an install notification with the
     * result when the worker finishes.
     */
    public void startAnalysis(String packageName, int notificationUid, @Nullable String appName) {
        markAnalysisAttempted(packageName);

        Data.Builder dataBuilder = new Data.Builder()
                .putString(TrackerAnalysisWorker.KEY_PACKAGE_NAME, packageName)
                .putInt(TrackerAnalysisWorker.KEY_NOTIFICATION_UID, notificationUid);
        if (appName != null)
            dataBuilder.putString(TrackerAnalysisWorker.KEY_APP_NAME, appName);

        Data inputData = dataBuilder.build();

        OneTimeWorkRequest workRequest = new OneTimeWorkRequest.Builder(TrackerAnalysisWorker.class)
                .setInputData(inputData)
                .addTag(packageName)
                .build();

        workManager.enqueueUniqueWork(
                getWorkName(packageName),
                ExistingWorkPolicy.KEEP,
                workRequest);
    }

    /**
     * Observe work status for the package's unique analysis work.
     */
    public LiveData<java.util.List<WorkInfo>> getWorkInfoByPackageLiveData(String packageName) {
        return workManager.getWorkInfosForUniqueWorkLiveData(getWorkName(packageName));
    }

    @Nullable
    public String getCachedResult(String packageName) {
        return getPrefs().getString(RESULT_PREFIX + packageName, null);
    }

    public boolean isCacheStale(String packageName) {
        try {
            PackageInfo pkg = getPackageInfo(packageName);
            SharedPreferences prefs = getPrefs();
            int cachedVersionCode = prefs.getInt(VERSION_PREFIX + packageName, Integer.MIN_VALUE);
            return pkg.versionCode > cachedVersionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return true;
        }
    }

    public boolean shouldStartAnalysis(String packageName) {
        return shouldStartAnalysis(getCachedResult(packageName), isCacheStale(packageName),
                hasAttemptedCurrentVersion(packageName));
    }

    /**
     * Saves analysis result to cache. Called by the Worker.
     */
    public void cacheResult(String packageName, String result, int versionCode) {
        getPrefs().edit()
                .putInt(VERSION_PREFIX + packageName, versionCode)
                .putString(RESULT_PREFIX + packageName, result)
                .apply();
    }

    /**
     * Forgets everything cached about a package: its analysis result, the
     * version that result describes, and the version last attempted.
     *
     * Call this only on a real uninstall. An app update deliberately keeps the
     * cache — the stale result is still shown, with a caveat, until the next
     * open re-analyses it — so the caller must exclude the PACKAGE_REMOVED
     * broadcast that an update sends.
     *
     * Static so it needs no WorkManager-backed instance: an uninstall broadcast
     * should not be the thing that first spins one up.
     */
    public static void clearCache(Context context, String packageName) {
        Context appContext = context.getApplicationContext();
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .remove(RESULT_PREFIX + packageName)
                .remove(VERSION_PREFIX + packageName)
                .remove(ATTEMPTED_VERSION_PREFIX + packageName)
                .apply();

        // Analysing a package that no longer exists can only fail. Best-effort:
        // losing the cancellation must not cost us the cache removal above.
        try {
            WorkManager.getInstance(appContext).cancelUniqueWork(getWorkName(packageName));
        } catch (Throwable ex) {
            Log.w(TAG, "Cannot cancel analysis for removed package=" + packageName + ": " + ex);
        }
    }

    public static int countTrackers(String result) {
        if (result == null)
            return 0;

        int count = 0;
        int index = result.indexOf("•");
        while (index >= 0) {
            count++;
            index = result.indexOf("•", index + 1);
        }

        return count;
    }

    static String getWorkName(String packageName) {
        return WORK_NAME_PREFIX + packageName;
    }

    static boolean shouldStartAnalysis(@Nullable String cachedResult, boolean cacheStale,
            boolean attemptedCurrentVersion) {
        return !attemptedCurrentVersion && (cachedResult == null || cacheStale);
    }

    private SharedPreferences getPrefs() {
        return mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private void markAnalysisAttempted(String packageName) {
        try {
            PackageInfo pkg = getPackageInfo(packageName);
            getPrefs().edit()
                    .putInt(ATTEMPTED_VERSION_PREFIX + packageName, pkg.versionCode)
                    .apply();
        } catch (PackageManager.NameNotFoundException ignored) {
        }
    }

    private boolean hasAttemptedCurrentVersion(String packageName) {
        try {
            PackageInfo pkg = getPackageInfo(packageName);
            int attemptedVersionCode = getPrefs().getInt(ATTEMPTED_VERSION_PREFIX + packageName,
                    Integer.MIN_VALUE);
            return attemptedVersionCode >= pkg.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private PackageInfo getPackageInfo(String packageName) throws PackageManager.NameNotFoundException {
        return mContext.getPackageManager().getPackageInfo(packageName, 0);
    }
}
