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
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manager class that handles caching, coordination, and background execution of
 * tracker library analysis.
 * Implements the Observer pattern to allow UI components to subscribe to
 * analysis updates.
 */
public class TrackerAnalysisManager {
    private static final String TAG = "TrackerAnalysisManager";
    private static final int EXODUS_DATABASE_VERSION = 423;
    private static final String PREFS_NAME = "library_analysis";

    private static TrackerAnalysisManager instance;
    private final Context mContext;
    private final ExecutorService executorService;
    private final Handler mainHandler;

    // Track active analyses with detailed state
    private final Map<String, AnalysisState> activeAnalyses = Collections.synchronizedMap(new HashMap<>());

    // Observers: PackageName -> Set<Listener>
    private final Map<String, Set<AnalysisStateListener>> listeners = Collections.synchronizedMap(new HashMap<>());

    private TrackerAnalysisManager(Context context) {
        this.mContext = context.getApplicationContext();
        this.executorService = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());

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
     * Subscribe to analysis updates for a specific package.
     * If an analysis is already running, the listener will be notified immediately.
     */
    public void addAnalysisListener(String packageName, AnalysisStateListener listener) {
        synchronized (listeners) {
            Set<AnalysisStateListener> pkgListeners = listeners.get(packageName);
            if (pkgListeners == null) {
                pkgListeners = Collections.synchronizedSet(new HashSet<>());
                listeners.put(packageName, pkgListeners);
            }
            pkgListeners.add(listener);
        }

        // If running, notify immediately
        AnalysisState state = activeAnalyses.get(packageName);
        if (state != null) {
            mainHandler.post(() -> {
                if (state == AnalysisState.QUEUED) {
                    listener.onAnalysisQueued();
                } else if (state == AnalysisState.RUNNING) {
                    listener.onAnalysisRunning();
                }
            });
        }
    }

    /**
     * Unsubscribe from analysis updates.
     */
    public void removeAnalysisListener(String packageName, AnalysisStateListener listener) {
        synchronized (listeners) {
            Set<AnalysisStateListener> pkgListeners = listeners.get(packageName);
            if (pkgListeners != null) {
                pkgListeners.remove(listener);
                if (pkgListeners.isEmpty()) {
                    listeners.remove(packageName);
                }
            }
        }
    }

    public boolean isAnalysisInProgress(String packageName) {
        return activeAnalyses.containsKey(packageName);
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
     * Starts an analysis for the given package in the background.
     * Updates are delivered to registered listeners.
     */
    public void startAnalysis(String packageName) {
        if (activeAnalyses.containsKey(packageName)) {
            Log.d(TAG, "Analysis already in progress for " + packageName);
            return;
        }

        // Mark as Queued initially
        activeAnalyses.put(packageName, AnalysisState.QUEUED);
        notifyQueued(packageName);

        executorService.submit(() -> {
            try {
                // Now really running
                activeAnalyses.put(packageName, AnalysisState.RUNNING);
                mainHandler.post(() -> notifyRunning(packageName));

                String result = runAnalysisSync(packageName, true);

                activeAnalyses.remove(packageName);
                mainHandler.post(() -> notifyFinished(packageName, result));
            } catch (Exception e) {
                Log.e(TAG, "Analysis failed for " + packageName, e);
                activeAnalyses.remove(packageName);
                mainHandler.post(() -> notifyFailed(packageName, e.getMessage()));
            }
        });
    }

    /**
     * Synchronous analysis method for use by background services.
     * Use startAnalysis() for UI tasks to ensure lifecycle safety.
     */
    public String runAnalysisSync(String packageName) throws Exception {
        return runAnalysisSync(packageName, false);
    }

    /**
     * worker logic.
     */
    private String runAnalysisSync(String packageName, boolean forceRefresh) throws Exception {
        PackageInfo pkg;
        try {
            pkg = mContext.getPackageManager().getPackageInfo(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            throw new Exception("Package not found: " + packageName);
        }

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
        analyser.setProgressCallback((current, total) -> {
            int percent = (int) ((current / (float) total) * 100);
            mainHandler.post(() -> notifyProgress(packageName, percent));
        });

        String result = analyser.analyseApp(packageName);

        // Cache result
        prefs.edit()
                .putInt("versioncode_" + packageName, pkg.versionCode)
                .putString("trackers_" + packageName, result)
                .apply();

        return result;
    }

    private void notifyQueued(String packageName) {
        synchronized (listeners) {
            Set<AnalysisStateListener> s = listeners.get(packageName);
            if (s != null) {
                for (AnalysisStateListener l : new HashSet<>(s)) {
                    l.onAnalysisQueued();
                }
            }
        }
    }

    private void notifyRunning(String packageName) {
        synchronized (listeners) {
            Set<AnalysisStateListener> s = listeners.get(packageName);
            if (s != null) {
                for (AnalysisStateListener l : new HashSet<>(s)) {
                    l.onAnalysisRunning();
                }
            }
        }
    }

    private void notifyProgress(String packageName, int percent) {
        synchronized (listeners) {
            Set<AnalysisStateListener> s = listeners.get(packageName);
            if (s != null) {
                for (AnalysisStateListener l : new HashSet<>(s)) {
                    l.onAnalysisProgress(percent);
                }
            }
        }
    }

    private void notifyFinished(String packageName, String result) {
        synchronized (listeners) {
            Set<AnalysisStateListener> s = listeners.get(packageName);
            if (s != null) {
                for (AnalysisStateListener l : new HashSet<>(s)) {
                    l.onAnalysisFinished(result);
                }
            }
        }
    }

    private void notifyFailed(String packageName, String message) {
        synchronized (listeners) {
            Set<AnalysisStateListener> s = listeners.get(packageName);
            if (s != null) {
                for (AnalysisStateListener l : new HashSet<>(s)) {
                    l.onAnalysisFailed(message);
                }
            }
        }
    }

    private SharedPreferences getPrefs() {
        return mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
