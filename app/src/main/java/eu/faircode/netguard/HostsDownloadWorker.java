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
 * Copyright © 2015–2020 Marcel Bokhorst (M66B)
 * Copyright © 2019–2026 Konrad Kollnig
 */

package eu.faircode.netguard;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import net.kollnig.missioncontrol.R;
import net.kollnig.missioncontrol.data.Blocklist;
import net.kollnig.missioncontrol.data.BlocklistManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.zip.GZIPInputStream;

public class HostsDownloadWorker extends Worker {
    private static final String TAG = "TrackerControl.Hosts";

    /** Unique name of the daily auto-update work. */
    static final String WORK_AUTO_UPDATE = "HostsUpdate";

    /**
     * Set as soon as a list's file has been replaced and cleared only once the
     * merged hosts file has been rebuilt from it. Without it, a run killed
     * between the two would leave a stale merged file that every later 304
     * would then treat as current.
     */
    static final String PREF_MERGE_PENDING = "hosts_merge_pending";

    /**
     * Schedule version the enqueued auto-update work was last built with. Bump
     * {@link #SCHEDULE_VERSION} whenever the constraints or the period below change,
     * so installs that already have the work enqueued are rescheduled once with the
     * new specification rather than keeping the old one until the user toggles the
     * setting off and on.
     */
    private static final String PREF_SCHEDULE_VERSION = "hosts_auto_update_schedule_version";
    private static final int SCHEDULE_VERSION = 1;

    /** Whether a single blocklist's body actually needed re-downloading. */
    enum DownloadOutcome {
        UPDATED,
        UNCHANGED
    }

    /**
     * Thrown from {@link #download} to abort a download promptly when
     * {@code isStopped()} fires mid-copy; caught by {@link #doWork} only, never treated
     * as a per-list failure.
     */
    private static final class StopRequestedException extends RuntimeException {
    }

    public HostsDownloadWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.i(TAG, "Starting hosts download");

        Context context = getApplicationContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        BlocklistManager manager = BlocklistManager.getInstance(context);
        List<Blocklist> lists = manager.getBlocklists();
        boolean anySuccess = false;
        boolean allSuccess = true;
        boolean anyChanged = false;

        for (Blocklist item : lists) {
            if (!item.enabled)
                continue;

            Log.i(TAG, "Downloading " + item.url);
            File tmp = new File(context.getFilesDir(), "blocklist_" + item.uuid + ".tmp");
            File target = manager.getBlocklistFile(item.uuid);

            HttpURLConnection connection = null;
            try {
                URL url = new URL(item.url);
                if (!"https".equalsIgnoreCase(url.getProtocol()))
                    throw new IOException("Only HTTPS blocklist URLs are supported");

                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestProperty("Accept-Encoding", "gzip");
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);

                DownloadOutcome outcome = download(item, connection, tmp, target, this::isStopped);

                if (outcome == DownloadOutcome.UPDATED) {
                    item.lastModified = new Date().getTime();
                    // Written durably, and before the validators that would let the
                    // next run answer 304 for every list: the merged file is out of
                    // date from here until the merge below succeeds.
                    prefs.edit().putBoolean(PREF_MERGE_PENDING, true).commit();
                    anyChanged = true;
                }
                manager.updateBlocklist(item);
                anySuccess = true;

            } catch (StopRequestedException stop) {
                return Result.failure();
            } catch (Throwable ex) {
                Log.e(TAG, "Failed to download " + item.url + ": " + ex.toString());
                item.lastDownloadSuccess = false;
                item.lastErrorMessage = ex.getMessage();
                manager.updateBlocklist(item);
                allSuccess = false;
            } finally {
                if (tmp.exists()) {
                    tmp.delete();
                }
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }

        if (anySuccess) {
            File hostsFile = new File(context.getFilesDir(), "hosts.txt");
            if (canSkipMerge(anyChanged, prefs.getBoolean(PREF_MERGE_PENDING, false), hostsFile.exists())) {
                // Every successful list came back unchanged and the merged file is
                // known to be built from them, so there is nothing to re-merge and no
                // reason to disturb live connections with a reload.
                String last = SimpleDateFormat.getDateTimeInstance().format(new Date().getTime());
                prefs.edit().putString("hosts_last_download", last).apply();

                Log.i(TAG, "Hosts unchanged, skipping merge and reload");
                return Result.success();
            }

            if (manager.mergeBlocklists()) {
                String last = SimpleDateFormat.getDateTimeInstance().format(new Date().getTime());
                prefs.edit()
                        .putString("hosts_last_download", last)
                        .putBoolean(PREF_MERGE_PENDING, false)
                        .commit();

                Log.i(TAG, "Hosts downloaded and merged successfully");
                ServiceSinkhole.reload("hosts file download", context, false);

                return allSuccess ? Result.success() : Result.success();
            } else {
                Log.e(TAG, "Merge failed");
                showNotification(context, "Hosts merge failed");
                return Result.failure();
            }
        } else {
            showNotification(context, "Hosts download failed");
            return Result.failure();
        }
    }

    /**
     * Whether the merged hosts file can be left alone: every successful list came
     * back unchanged, no earlier run left a replacement unmerged, and a merged file
     * exists to leave alone in the first place.
     */
    static boolean canSkipMerge(boolean anyChanged, boolean mergePending, boolean mergedFileExists) {
        return !anyChanged && !mergePending && mergedFileExists;
    }

    /**
     * Brings an already-enqueued auto-update in line with the current schedule
     * version, once per version. Called from application startup, since the
     * preference listener only runs when the user changes the setting.
     */
    static void reconcileAutoUpdate(Context context, SharedPreferences prefs) {
        if (prefs.getInt(PREF_SCHEDULE_VERSION, 0) >= SCHEDULE_VERSION)
            return;

        // Nothing is enqueued while the setting is off, and switching it on goes
        // through scheduleAutoUpdate anyway, so only record the version there.
        if (prefs.getBoolean("hosts_auto_update", false))
            scheduleAutoUpdate(context, true);
        else
            prefs.edit().putInt(PREF_SCHEDULE_VERSION, SCHEDULE_VERSION).apply();
    }

    /**
     * Enqueues or cancels the daily blocklist update to match {@code enabled}.
     * <p>
     * Enqueued with {@link ExistingPeriodicWorkPolicy#UPDATE} rather than
     * {@code KEEP} so an install that switched auto-update on under an older
     * version keeps its schedule but picks up the current constraints, instead of
     * carrying the old ones until the user toggles the setting.
     */
    static void scheduleAutoUpdate(Context context, boolean enabled) {
        WorkManager workManager = WorkManager.getInstance(context);
        if (!enabled) {
            workManager.cancelUniqueWork(WORK_AUTO_UPDATE);
            return;
        }

        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build();
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(HostsDownloadWorker.class, 24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build();
        workManager.enqueueUniquePeriodicWork(WORK_AUTO_UPDATE, ExistingPeriodicWorkPolicy.UPDATE, request);
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putInt(PREF_SCHEDULE_VERSION, SCHEDULE_VERSION).apply();
    }

    /**
     * Downloads a single blocklist over an already-opened connection.
     * <p>
     * When {@code target} exists, sends {@code If-None-Match}/{@code If-Modified-Since}
     * from {@code item}'s stored validators (a missing target is always fetched
     * unconditionally, never conditionally). A {@code 304} response leaves
     * {@code target} untouched and returns {@link DownloadOutcome#UNCHANGED}. A
     * {@code 200} response is written to {@code tmp}, renamed onto {@code target}, has
     * its {@code ETag}/{@code Last-Modified} response headers stored on {@code item}
     * (either may end up null if the server stops sending them) and returns
     * {@link DownloadOutcome#UPDATED}. Any other response code, or a failed rename,
     * throws {@link IOException}.
     */
    static DownloadOutcome download(Blocklist item, HttpURLConnection connection, File tmp, File target,
            BooleanSupplier isStopped) throws IOException {
        if (target.exists()) {
            if (item.etag != null)
                connection.setRequestProperty("If-None-Match", item.etag);
            if (item.lastModifiedHeader != null)
                connection.setRequestProperty("If-Modified-Since", item.lastModifiedHeader);
        }

        connection.connect();

        int responseCode = connection.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
            item.lastDownloadSuccess = true;
            item.lastErrorMessage = null;
            return DownloadOutcome.UNCHANGED;
        }
        if (responseCode != HttpURLConnection.HTTP_OK)
            throw new IOException(responseCode + " " + connection.getResponseMessage());

        InputStream in = null;
        OutputStream out = null;
        try {
            in = "gzip".equals(connection.getContentEncoding())
                    ? new GZIPInputStream(connection.getInputStream())
                    : connection.getInputStream();
            out = new FileOutputStream(tmp);

            byte[] buffer = new byte[4096];
            int bytes;
            while ((bytes = in.read(buffer)) != -1) {
                if (isStopped.getAsBoolean())
                    throw new StopRequestedException();
                out.write(buffer, 0, bytes);
            }
        } finally {
            if (out != null)
                out.close();
            if (in != null)
                in.close();
        }

        if (target.exists())
            target.delete();
        if (!tmp.renameTo(target))
            throw new IOException("Failed to save file");

        item.etag = connection.getHeaderField("ETag");
        item.lastModifiedHeader = connection.getHeaderField("Last-Modified");
        item.lastDownloadSuccess = true;
        item.lastErrorMessage = null;

        return DownloadOutcome.UPDATED;
    }

    private void showNotification(Context context, String message) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            android.app.NotificationManager nm = (android.app.NotificationManager) context
                    .getSystemService(Context.NOTIFICATION_SERVICE);
            android.app.NotificationChannel channel = new android.app.NotificationChannel("notify",
                    context.getString(R.string.channel_notify), android.app.NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(channel);
        }

        androidx.core.app.NotificationCompat.Builder builder = new androidx.core.app.NotificationCompat.Builder(context,
                "notify")
                .setSmallIcon(R.drawable.ic_shield_off)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(message)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true);

        Util.notify(context, 2024, builder.build());
    }
}
