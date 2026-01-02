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
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.Date;
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
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String urlString = prefs.getString("hosts_url_new", net.kollnig.missioncontrol.BuildConfig.HOSTS_FILE_URI);

        File file = new File(context.getFilesDir(), "hosts.txt");
        File tmp = new File(context.getFilesDir(), "hosts.tmp");

        InputStream in = null;
        OutputStream out = null;
        URLConnection connection = null;

        try {
            URL url = new URL(urlString);
            Log.i(TAG, "Downloading " + url + " into " + tmp);

            connection = url.openConnection();
            connection.setRequestProperty("Accept-Encoding", "gzip");
            connection.connect();

            if (connection instanceof HttpURLConnection) {
                HttpURLConnection httpConnection = (HttpURLConnection) connection;
                if (httpConnection.getResponseCode() != HttpURLConnection.HTTP_OK)
                    throw new IOException(httpConnection.getResponseCode() + " " + httpConnection.getResponseMessage());
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

            if (file.exists()) {
                file.delete();
            }
            if (tmp.renameTo(file)) {
                String last = SimpleDateFormat.getDateTimeInstance().format(new Date().getTime());
                prefs.edit().putString("hosts_last_download", last).apply();
                Log.i(TAG, "Hosts downloaded successfully");

                ServiceSinkhole.reload("hosts file download", context, false);
                return Result.success();
            } else {
                Log.e(TAG, "Failed to rename temp file");
                return Result.failure();
            }

        } catch (Throwable ex) {
            Log.e(TAG, ex + "\n" + Log.getStackTraceString(ex));
            return Result.failure();
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
}
