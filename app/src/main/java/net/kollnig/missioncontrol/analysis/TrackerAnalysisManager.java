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

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Manager class that handles caching and coordination of tracker library analysis.
 */
public class TrackerAnalysisManager {
    private static final int EXODUS_DATABASE_VERSION = 423;
    private static final String PREFS_NAME = "library_analysis";
    
    private static TrackerAnalysisManager instance;
    private final Context mContext;
    private final Set<String> runningAnalyses = Collections.synchronizedSet(new HashSet<>());
    
    private TrackerAnalysisManager(Context context) {
        this.mContext = context.getApplicationContext();
        
        // Initialize/update database version
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
     * Check if an analysis is currently running for the given package.
     */
    public boolean isAnalysisInProgress(String packageName) {
        return runningAnalyses.contains(packageName);
    }
    
    @Nullable
    public String getCachedResult(String packageName) {
        return getPrefs().getString("trackers_" + packageName, null);
    }
    
    public String analyse(String packageName) throws AnalysisException {
        return analyse(packageName, false, null);
    }
    
    public String analyse(String packageName, boolean forceRefresh) throws AnalysisException {
        return analyse(packageName, forceRefresh, null);
    }

    /**
     * Mark an analysis as starting. Call this BEFORE starting the async task.
     * @return true if marked successfully, false if already running
     */
    public boolean markAnalysisStarting(String packageName) {
        return runningAnalyses.add(packageName);
    }
    
    /**
     * Mark an analysis as finished. Call this when async task completes.
     */
    public void markAnalysisFinished(String packageName) {
        runningAnalyses.remove(packageName);
    }

    public String analyse(String packageName, boolean forceRefresh, AnalysisProgressCallback progressCallback) throws AnalysisException {
        try {
            PackageInfo pkg = mContext.getPackageManager().getPackageInfo(packageName, 0);
            SharedPreferences prefs = getPrefs();
            int cachedVersionCode = prefs.getInt("versioncode_" + packageName, Integer.MIN_VALUE);
            
            // Check cache first
            if (!forceRefresh && pkg.versionCode <= cachedVersionCode) {
                String cached = prefs.getString("trackers_" + packageName, null);
                if (cached != null) {
                    return cached;
                }
            }
            
            // Perform analysis
            TrackerLibraryAnalyser analyser = new TrackerLibraryAnalyser(mContext);
            if (progressCallback != null) {
                analyser.setProgressCallback(progressCallback);
            }
            String result = analyser.analyseApp(packageName);
            
            // Cache result
            prefs.edit()
                    .putInt("versioncode_" + packageName, pkg.versionCode)
                    .putString("trackers_" + packageName, result)
                    .apply();
            
            return result;
            
        } catch (PackageManager.NameNotFoundException e) {
            throw new AnalysisException("Package not found: " + packageName);
        }
    }
    
    private SharedPreferences getPrefs() {
        return mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
