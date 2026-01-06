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

import static net.kollnig.missioncontrol.DetailsActivity.INTENT_EXTRA_APP_NAME;
import static net.kollnig.missioncontrol.DetailsActivity.INTENT_EXTRA_APP_PACKAGENAME;
import static net.kollnig.missioncontrol.DetailsActivity.INTENT_EXTRA_APP_UID;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import net.kollnig.missioncontrol.DetailsActivity;
import net.kollnig.missioncontrol.R;

import org.apache.commons.lang3.StringUtils;

import java.util.concurrent.atomic.AtomicInteger;

import eu.faircode.netguard.PendingIntentCompat;
import eu.faircode.netguard.ServiceSinkhole;

public class TrackerAnalysisWorker extends Worker {
    private static final String TAG = "TrackerControl.Analysis";
    
    public static final String KEY_PACKAGE_NAME = "package_name";
    public static final String KEY_RESULT = "result";
    public static final String KEY_ERROR = "error";
    public static final String KEY_PROGRESS = "progress";
    
    // Base notification ID for analysis completion notifications
    // Use package hash offset to avoid conflicts with other notification IDs
    private static final int NOTIFICATION_ID_BASE = 20000;

    public TrackerAnalysisWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        String packageName = getInputData().getString(KEY_PACKAGE_NAME);
        if (packageName == null) {
            return Result.failure(new Data.Builder()
                    .putString(KEY_ERROR, "No package name provided")
                    .build());
        }

        try {
            Context context = getApplicationContext();
            PackageInfo pkg = context.getPackageManager().getPackageInfo(packageName, 0);

            // Perform analysis with progress reporting
            String result = doAnalysis(context, packageName);

            // Cache the result
            TrackerAnalysisManager.getInstance(context)
                    .cacheResult(packageName, result, pkg.versionCode);

            // Show notification when analysis is finished successfully
            // Only show if result is not null (analysis completed successfully)
            if (result != null) {
                showCompletionNotification(context, packageName, result, pkg);
            }

            return Result.success(new Data.Builder()
                    .putString(KEY_RESULT, result)
                    .build());

        } catch (PackageManager.NameNotFoundException e) {
            return Result.failure(new Data.Builder()
                    .putString(KEY_ERROR, "Package not found: " + packageName)
                    .build());
        } catch (AnalysisException e) {
            return Result.failure(new Data.Builder()
                    .putString(KEY_ERROR, e.getMessage())
                    .build());
        } catch (Exception e) {
            return Result.failure(new Data.Builder()
                    .putString(KEY_ERROR, e.getMessage() != null ? e.getMessage() : "Unknown error")
                    .build());
        }
    }

    private String doAnalysis(Context context, String packageName) throws AnalysisException {
        TrackerLibraryAnalyser analyser = new TrackerLibraryAnalyser(context);
        AtomicInteger lastProgress = new AtomicInteger(-1);
        analyser.setProgressCallback((current, total) -> {
            int percent = (int) ((current / (float) total) * 100);
            if (lastProgress.getAndSet(percent) != percent) {
                setProgressAsync(new Data.Builder()
                        .putInt(KEY_PROGRESS, percent)
                        .build());
            }
        });
        return analyser.analyseApp(packageName);
    }

    private void showCompletionNotification(Context context, String packageName, String result, PackageInfo pkg) {
        try {
            // Create notification channel for Android O+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                NotificationChannel channel = new NotificationChannel("analysis",
                        context.getString(R.string.static_analysis),
                        NotificationManager.IMPORTANCE_DEFAULT);
                nm.createNotificationChannel(channel);
            }

            // Get app name
            PackageManager pm = context.getPackageManager();
            ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
            String appName = pm.getApplicationLabel(appInfo).toString();

            // Count trackers found
            // Note: This uses the same bullet character counting method as ServiceSinkhole
            // for consistency with the existing notification system
            int trackerCount = StringUtils.countMatches(result, "•");

            // Build notification
            Intent main = new Intent(context, DetailsActivity.class);
            main.putExtra(INTENT_EXTRA_APP_NAME, appName);
            main.putExtra(INTENT_EXTRA_APP_PACKAGENAME, packageName);
            main.putExtra(INTENT_EXTRA_APP_UID, pkg.applicationInfo.uid);
            PendingIntent pi = PendingIntentCompat.getActivity(context, 0, main, PendingIntent.FLAG_UPDATE_CURRENT);

            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, "analysis")
                    .setSmallIcon(R.drawable.ic_rocket_white)
                    .setContentTitle(context.getString(R.string.static_analysis))
                    .setContentText(context.getString(R.string.msg_installed_tracker_libraries_found, trackerCount))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT);

            // Show notification with unique ID based on package name
            // Using hashCode may have collisions but is acceptable since:
            // 1. Package names are unique per device
            // 2. Collision only affects notification grouping, not functionality
            // 3. Adding base offset reduces collision risk with other notification IDs
            int notificationId = NOTIFICATION_ID_BASE + Math.abs(packageName.hashCode());
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(notificationId, builder.build());

        } catch (Exception e) {
            // Don't fail the worker if notification fails
            Log.e(TAG, "Failed to show completion notification: " + e.getMessage(), e);
        }
    }
}
