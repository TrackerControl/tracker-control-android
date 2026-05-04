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

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import eu.faircode.netguard.PendingIntentCompat;
import eu.faircode.netguard.Util;
import net.kollnig.missioncontrol.DetailsActivity;
import net.kollnig.missioncontrol.R;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Semaphore;

public class TrackerAnalysisWorker extends Worker {
    private static final String TAG = TrackerAnalysisWorker.class.getSimpleName();
    private static final Semaphore ANALYSIS_SEMAPHORE = new Semaphore(1);

    public static final String KEY_PACKAGE_NAME = "package_name";
    public static final String KEY_NOTIFICATION_UID = "notification_uid";
    public static final String KEY_APP_NAME = "app_name";
    public static final String KEY_RESULT = "result";
    public static final String KEY_ERROR = "error";
    public static final String KEY_PROGRESS = "progress";

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

        boolean acquired = false;
        try {
            Context context = getApplicationContext();
            PackageInfo pkg = context.getPackageManager().getPackageInfo(packageName, 0);

            ANALYSIS_SEMAPHORE.acquire();
            acquired = true;

            // Perform analysis with progress reporting
            String result = doAnalysis(context, packageName);

            // Cache the result
            TrackerAnalysisManager.getInstance(context)
                    .cacheResult(packageName, result, pkg.versionCode);

            updateInstallNotification(context, packageName, result);

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
        } finally {
            if (acquired)
                ANALYSIS_SEMAPHORE.release();
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

    private void updateInstallNotification(Context context, String packageName, String result) {
        int uid = getInputData().getInt(KEY_NOTIFICATION_UID, -1);
        if (uid < 0 || !Util.canNotify(context))
            return;

        String appName = getInputData().getString(KEY_APP_NAME);
        if (appName == null)
            appName = packageName;

        try {
            NotificationManagerCompat.from(context).notify(uid,
                    buildInstallNotification(context, packageName, uid, appName, result));
        } catch (SecurityException ex) {
            Log.w(TAG, "SecurityException updating install notification for uid " + uid + ": " + ex.getMessage());
        }
    }

    static Notification buildInstallNotification(Context context, String packageName, int uid, String appName,
            String result) {
        int trackerCount = TrackerAnalysisManager.countTrackers(result);

        Intent main = new Intent(context, DetailsActivity.class);
        main.putExtra(INTENT_EXTRA_APP_NAME, appName);
        main.putExtra(INTENT_EXTRA_APP_PACKAGENAME, packageName);
        main.putExtra(INTENT_EXTRA_APP_UID, uid);
        PendingIntent pi = PendingIntentCompat.getActivity(context, uid, main,
                PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, "notify");
        builder.setSmallIcon(R.drawable.ic_rocket_white)
                .setContentIntent(pi)
                .addAction(0, context.getString(R.string.title_activity_detail), pi)
                .setColor(context.getResources().getColor(R.color.colorTrackerControl))
                .setAutoCancel(true);
        builder.setContentTitle(context.getString(R.string.msg_installed, appName))
                .setContentText(context.getString(R.string.msg_installed_tracker_libraries_found, trackerCount));

        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + packageName));
        PendingIntent piUninstall = PendingIntentCompat.getActivity(context, uid + 10000, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
        builder.addAction(0, context.getString(R.string.uninstall), piUninstall);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            builder.setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setVisibility(NotificationCompat.VISIBILITY_SECRET);

        return builder.build();
    }
}
