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
    private static final int EXODUS_DATABASE_VERSION = 423;
    private static final String PREFS_NAME = "library_analysis";
    // Single work name ensures only one analysis runs at a time (prevents OOM)
    private static final String WORK_NAME = "tracker_analysis";

    private static TrackerAnalysisManager instance;
    private final Context mContext;
    private final WorkManager workManager;

    private TrackerAnalysisManager(Context context) {
        this.mContext = context.getApplicationContext();
        this.workManager = WorkManager.getInstance(mContext);

        // Initialize/update database version - clears cache if Exodus DB updated
        SharedPreferences prefs = getPrefs();
        int current = prefs.getInt("version", Integer.MIN_VALUE);
        if (current < EXODUS_DATABASE_VERSION) {
            prefs.edit().clear().putInt("version", EXODUS_DATABASE_VERSION).apply();
        }
    }

    public static synchronized TrackerAnalysisManager getInstance(Context context) {
        if (instance == null) {
            instance = new TrackerAnalysisManager(context);
        }
        return instance;
    }

    /**
     * Starts an analysis for the given package using WorkManager.
     * Only one analysis runs at a time to prevent OOM; others are queued.
     * Observe progress via {@link #getWorkInfoByPackageLiveData(String)}.
     *
     * @param packageName The package to analyze
     */
    public void startAnalysis(String packageName) {
        Data inputData = new Data.Builder()
                .putString(TrackerAnalysisWorker.KEY_PACKAGE_NAME, packageName)
                .build();

        OneTimeWorkRequest workRequest = new OneTimeWorkRequest.Builder(TrackerAnalysisWorker.class)
                .setInputData(inputData)
                .addTag(packageName)
                .build();

        // Use global work name + APPEND to serialize all analyses (prevents OOM from
        // concurrent scans)
        workManager.enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                workRequest);
    }

    /**
     * Observe work status for a given package (by tag).
     */
    public LiveData<java.util.List<WorkInfo>> getWorkInfoByPackageLiveData(String packageName) {
        return workManager.getWorkInfosByTagLiveData(packageName);
    }

    @Nullable
    public String getCachedResult(String packageName) {
        return getPrefs().getString("trackers_" + packageName, null);
    }

    public boolean isCacheStale(String packageName) {
        try {
            PackageInfo pkg = mContext.getPackageManager().getPackageInfo(packageName, 0);
            SharedPreferences prefs = getPrefs();
            int cachedVersionCode = prefs.getInt("versioncode_" + packageName, Integer.MIN_VALUE);
            return pkg.versionCode > cachedVersionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return true;
        }
    }

    /**
     * Saves analysis result to cache. Called by the Worker.
     */
    public void cacheResult(String packageName, String result, int versionCode) {
        getPrefs().edit()
                .putInt("versioncode_" + packageName, versionCode)
                .putString("trackers_" + packageName, result)
                .apply();
    }

    private SharedPreferences getPrefs() {
        return mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
