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

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;
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
import java.net.URLConnection;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.zip.GZIPInputStream;

public class HostsDownloadWorker extends Worker {
    private static final String TAG = "TrackerControl.Hosts";

    public HostsDownloadWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.i(TAG, "Starting hosts download");

        Context context = getApplicationContext();
        BlocklistManager manager = BlocklistManager.getInstance(context);
        List<Blocklist> lists = manager.getBlocklists();
        boolean anySuccess = false;
        boolean allSuccess = true;

        for (Blocklist item : lists) {
            if (!item.enabled)
                continue;

            Log.i(TAG, "Downloading " + item.url);
            File tmp = new File(context.getFilesDir(), "blocklist_" + item.uuid + ".tmp");
            File target = manager.getBlocklistFile(item.uuid);

            InputStream in = null;
            OutputStream out = null;
            URLConnection connection = null;

            try {
                URL url = new URL(item.url);
                connection = url.openConnection();
                connection.setRequestProperty("Accept-Encoding", "gzip");
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);
                connection.connect();

                if (connection instanceof HttpURLConnection) {
                    HttpURLConnection httpConnection = (HttpURLConnection) connection;
                    if (httpConnection.getResponseCode() != HttpURLConnection.HTTP_OK)
                        throw new IOException(
                                httpConnection.getResponseCode() + " " + httpConnection.getResponseMessage());
                }

                if ("gzip".equals(connection.getContentEncoding()))
                    in = new GZIPInputStream(connection.getInputStream());
                else
                    in = connection.getInputStream();

                out = new FileOutputStream(tmp);

                byte[] buffer = new byte[4096];
                int bytes;
                while ((bytes = in.read(buffer)) != -1) {
                    if (isStopped()) {
                        return Result.failure();
                    }
                    out.write(buffer, 0, bytes);
                }

                if (target.exists()) {
                    target.delete();
                }
                if (tmp.renameTo(target)) {
                    item.lastModified = new Date().getTime();
                    item.lastDownloadSuccess = true;
                    item.lastErrorMessage = null;
                    manager.updateBlocklist(item);
                    anySuccess = true;
                } else {
                    Log.e(TAG, "Failed to rename temp file for " + item.url);
                    item.lastDownloadSuccess = false;
                    item.lastErrorMessage = "Failed to save file";
                    manager.updateBlocklist(item);
                    allSuccess = false;
                }

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
                try {
                    if (out != null)
                        out.close();
                } catch (IOException ex) {
                    Log.e(TAG, ex.toString());
                }
                try {
                    if (in != null)
                        in.close();
                } catch (IOException ex) {
                    Log.e(TAG, ex.toString());
                }
                if (connection instanceof HttpURLConnection) {
                    ((HttpURLConnection) connection).disconnect();
                }
            }
        }

        if (anySuccess) {
            if (manager.mergeBlocklists()) {
                String last = SimpleDateFormat.getDateTimeInstance().format(new Date().getTime());
                PreferenceManager.getDefaultSharedPreferences(context).edit()
                        .putString("hosts_last_download", last).apply();

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

        androidx.core.app.NotificationManagerCompat.from(context).notify(2024, builder.build());
    }
}
