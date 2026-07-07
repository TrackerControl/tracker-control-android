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
 */

package net.kollnig.missioncontrol.patch;

import static net.kollnig.missioncontrol.DetailsActivity.INTENT_EXTRA_APP_NAME;
import static net.kollnig.missioncontrol.DetailsActivity.INTENT_EXTRA_APP_PACKAGENAME;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.lifecycle.LiveData;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import eu.faircode.netguard.PendingIntentCompat;
import eu.faircode.netguard.Util;
import net.kollnig.missioncontrol.DetailsActivity;
import net.kollnig.missioncontrol.R;

import java.io.File;
import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * Runs the {@link ApkPatcher} off the UI thread as background WorkManager work,
 * mirroring {@code TrackerAnalysisWorker}. Progress is surfaced two ways: an
 * ongoing, silent notification (updated as each phase reports in) and
 * {@code setProgressAsync}, which any open {@link DetailsActivity} can observe
 * via {@link #getWorkInfo(Context, String)}. On completion the patched split
 * set is installed and a final notification is posted.
 */
public class ApkPatchWorker extends Worker {

    private static final String TAG = ApkPatchWorker.class.getSimpleName();
    private static final String WORK_NAME_PREFIX = "apk_patch_";
    /** Patching is memory/CPU heavy; run at most one at a time. */
    private static final Semaphore PATCH_SEMAPHORE = new Semaphore(1);

    public static final String KEY_PACKAGE_NAME = "package_name";
    public static final String KEY_APP_NAME = "app_name";
    public static final String KEY_ERROR = "error";
    public static final String KEY_PROGRESS_MESSAGE = "progress_message";

    public ApkPatchWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    /** Enqueue a background patch for {@code packageName}; duplicates are ignored. */
    public static void enqueue(@NonNull Context ctx, @NonNull String packageName,
                               @Nullable String appName) {
        Data data = new Data.Builder()
                .putString(KEY_PACKAGE_NAME, packageName)
                .putString(KEY_APP_NAME, appName == null ? packageName : appName)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(ApkPatchWorker.class)
                .setInputData(data)
                .addTag(packageName)
                .build();
        WorkManager.getInstance(ctx).enqueueUniqueWork(
                WORK_NAME_PREFIX + packageName, ExistingWorkPolicy.KEEP, request);
    }

    /** Observe the patch work for {@code packageName} (state + progress message). */
    public static LiveData<List<WorkInfo>> getWorkInfo(@NonNull Context ctx,
                                                       @NonNull String packageName) {
        return WorkManager.getInstance(ctx)
                .getWorkInfosForUniqueWorkLiveData(WORK_NAME_PREFIX + packageName);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        String packageName = getInputData().getString(KEY_PACKAGE_NAME);
        if (packageName == null)
            return Result.failure(error("No package name provided"));

        String appName = getInputData().getString(KEY_APP_NAME);
        if (appName == null) appName = packageName;
        final String app = appName;
        int notifId = (WORK_NAME_PREFIX + packageName).hashCode();

        boolean acquired = false;
        try {
            ApkPatcher patcher = PatcherFactory.get();
            if (!patcher.isAvailable())
                return Result.failure(error(ctx.getString(R.string.patch_unavailable)));

            ApplicationInfo ai = ctx.getPackageManager().getApplicationInfo(packageName, 0);
            File base = new File(ai.sourceDir);

            PATCH_SEMAPHORE.acquire();
            acquired = true;

            notifyProgress(ctx, notifId, app, ctx.getString(R.string.patch_progress));

            File outputDir = new File(ctx.getCacheDir(), "apk-patcher/out");
            deleteRecursively(outputDir);
            outputDir.mkdirs();

            PatchResult result = patcher.patch(ctx, packageName, base, outputDir, msg -> {
                if (msg == null) return;
                setProgressAsync(new Data.Builder()
                        .putString(KEY_PROGRESS_MESSAGE, msg).build());
                notifyProgress(ctx, notifId, app, msg);
            });

            if (result.status != PatchResult.Status.SUCCESS)
                return finishFailure(ctx, notifId, app, result.message);

            notifyProgress(ctx, notifId, app, ctx.getString(R.string.patch_installing));
            SplitApkInstaller.install(ctx, packageName, result.outputFiles);

            notifyDone(ctx, notifId, packageName, app);
            return Result.success(new Data.Builder()
                    .putString(KEY_PROGRESS_MESSAGE,
                            ctx.getString(R.string.patch_installing)).build());

        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return finishFailure(ctx, notifId, app, msg);
        } finally {
            if (acquired) PATCH_SEMAPHORE.release();
        }
    }

    private Result finishFailure(Context ctx, int notifId, String appName, String message) {
        notifyFailed(ctx, notifId, appName, message);
        return Result.failure(error(message == null ? "Unknown error" : message));
    }

    private static Data error(String message) {
        return new Data.Builder().putString(KEY_ERROR, message).build();
    }

    // --- Notifications ---------------------------------------------------

    /** Ongoing, silent progress notification on the low-importance channel. */
    private static void notifyProgress(Context ctx, int notifId, String appName, String message) {
        if (!Util.canNotify(ctx)) return;
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, "foreground")
                .setSmallIcon(R.drawable.ic_rocket_white)
                .setColor(ctx.getResources().getColor(R.color.colorTrackerControl))
                .setContentTitle(ctx.getString(R.string.patch_title, appName))
                .setContentText(message)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(0, 0, true)
                .setPriority(NotificationCompat.PRIORITY_LOW);
        safeNotify(ctx, notifId, b.build());
    }

    private static void notifyDone(Context ctx, int notifId, String packageName, String appName) {
        if (!Util.canNotify(ctx)) return;
        Intent open = new Intent(ctx, DetailsActivity.class)
                .putExtra(INTENT_EXTRA_APP_NAME, appName)
                .putExtra(INTENT_EXTRA_APP_PACKAGENAME, packageName);
        PendingIntent pi = PendingIntentCompat.getActivity(ctx, notifId, open,
                PendingIntent.FLAG_UPDATE_CURRENT);
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, "notify")
                .setSmallIcon(R.drawable.ic_rocket_white)
                .setColor(ctx.getResources().getColor(R.color.colorTrackerControl))
                .setContentTitle(ctx.getString(R.string.patch_title, appName))
                .setContentText(ctx.getString(R.string.patch_install_success))
                .setContentIntent(pi)
                .setAutoCancel(true);
        safeNotify(ctx, notifId, b.build());
    }

    private static void notifyFailed(Context ctx, int notifId, String appName, String message) {
        if (!Util.canNotify(ctx)) return;
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, "notify")
                .setSmallIcon(R.drawable.ic_rocket_white)
                .setColor(ctx.getResources().getColor(R.color.colorTrackerControl))
                .setContentTitle(ctx.getString(R.string.patch_title, appName))
                .setContentText(ctx.getString(R.string.patch_failed,
                        message == null ? "" : message))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(
                        ctx.getString(R.string.patch_failed, message == null ? "" : message)))
                .setAutoCancel(true);
        safeNotify(ctx, notifId, b.build());
    }

    private static void safeNotify(Context ctx, int notifId, Notification n) {
        try {
            NotificationManagerCompat.from(ctx).notify(notifId, n);
        } catch (SecurityException ex) {
            Log.w(TAG, "Cannot post patch notification: " + ex.getMessage());
        }
    }

    private static void deleteRecursively(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        f.delete();
    }
}
