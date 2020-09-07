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
 * Copyright © 2019–2020 Konrad Kollnig (University of Oxford)
 */

package net.kollnig.missioncontrol;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.snackbar.Snackbar;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;
import java.util.List;

public class Common {
    @Nullable
    public static String fetch(String url) {
        try {
            StringBuilder html = new StringBuilder();

            HttpURLConnection conn = (HttpURLConnection) (new URL(url)).openConnection();
            conn.setConnectTimeout(5000);
            BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));

            String str;
            while ((str = in.readLine()) != null)
                html.append(str);

            in.close();

            return html.toString();
        } catch (IOException e) {
            return null;
        }
    }

    public static Intent adSettings() {
        Intent intent = new Intent();
        return intent.setComponent(new ComponentName("com.google.android.gms", "com.google.android.gms.ads.settings.AdsSettingsActivity"));
    }

    public static Intent browse(String url) {
        if (!url.startsWith("http://") && !url.startsWith("https://"))
            url = "http://" + url;

        return new Intent(Intent.ACTION_VIEW, Uri.parse(url));
    }

    public static boolean hasAdSettings(Context c) {
        return isCallable(c, adSettings());
    }

    public static Intent emailIntent(@Nullable String email, String subject, String body) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("message/rfc822");
        if (email != null) {
            i.putExtra(Intent.EXTRA_EMAIL, new String[]{email});
        }
        i.putExtra(Intent.EXTRA_SUBJECT, subject);
        i.putExtra(Intent.EXTRA_TEXT, body);
        return i;
    }

    public static boolean isCallable(Context c, Intent intent) {
        List<ResolveInfo> list = c.getPackageManager().queryIntentActivities(intent,
                PackageManager.MATCH_DEFAULT_ONLY);
        return list.size() > 0;
    }

    /**
     * Retrieves the name of the app based on the given uid
     *
     * @param uid - of the app
     * @return the name of the package of the app with the given uid, or "Unknown" if
     * no name could be found for the uid.
     */
    public static String getAppName(PackageManager pm, int uid) {
        /* IMPORTANT NOTE:
         * From https://source.android.com/devices/tech/security/ : "The Android
         * system assigns a unique user ID (UID) to each Android application and
         * runs it as that user in a separate process"
         *
         * However, there is an exception: "A closer relationship with a shared
         * Application Sandbox is allowed via the shared UID feature where two
         * or more applications signed with same developer key can declare a
         * shared UID in their manifest."
         */

        // See if this is root
        if (uid == 0)
            return "System";

        // If we can't find a running app, just get a list of packages that map to the uid
        String[] packages = pm.getPackagesForUid(uid);
        if (packages != null && packages.length > 0)
            return packages[0];

        return "Unknown";
    }

    public static Intent getLaunchIntent(Activity activity, String appId) {
        Intent intent = activity.getPackageManager().getLaunchIntentForPackage(appId);
        return intent == null ||
                intent.resolveActivity(activity.getPackageManager()) == null ? null : intent;
    }

    @Nullable
    public static Snackbar getSnackbar(Activity activity, int msg) {
        View v = activity.getWindow().getDecorView().findViewById(android.R.id.content);
        if (v == null)
            return null;
        Snackbar s = Snackbar.make(v,
                msg,
                Snackbar.LENGTH_LONG);
        s.setActionTextColor(ContextCompat.getColor(activity, R.color.colorPrimary));
        return s;
    }

    public static int dayOfYear() {
        Calendar calendar = Calendar.getInstance();
        return calendar.get(Calendar.DAY_OF_YEAR);
    }
}
