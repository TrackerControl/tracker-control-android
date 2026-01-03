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
package net.kollnig.missioncontrol

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.view.View
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.zip.GZIPInputStream

object Common {
    /**
     * Downloads content from a provided URL
     *
     * @param url URL for download
     * @return Downloaded content
     */
    @JvmStatic
    fun fetch(url: String?): String? {
        try {
            val html = StringBuilder()

            val conn = (URL(url)).openConnection() as HttpURLConnection
            conn.setRequestProperty("Accept-Encoding", "gzip")
            conn.setConnectTimeout(5000)
            val `in`: BufferedReader?
            if ("gzip" == conn.getContentEncoding()) `in` =
                BufferedReader(InputStreamReader(GZIPInputStream(conn.getInputStream())))
            else `in` = BufferedReader(InputStreamReader(conn.getInputStream()))

            var str: String?
            while ((`in`.readLine().also { str = it }) != null) html.append(str)

            `in`.close()

            return html.toString()
        } catch (e: IOException) {
            return null
        } catch (e: OutOfMemoryError) {
            return null
        }
    }

    /**
     * Returns an intent to the system ad settings
     *
     * @return Intent to open system ad settings
     */
    @JvmStatic
    fun adSettings(): Intent {
        val intent = Intent()
        return intent.setComponent(
            ComponentName(
                "com.google.android.gms",
                "com.google.android.gms.ads.settings.AdsSettingsActivity"
            )
        )
    }

    /**
     * Returns an intent to open a URL in a browser
     *
     * @param url A URL to be opened
     * @return An intent to open the provided URL
     */
    @JvmStatic
    fun browse(url: String): Intent {
        var url = url
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://" + url

        return Intent(Intent.ACTION_VIEW, Uri.parse(url))
    }

    /**
     * Check if system ad settings exist
     *
     * @param c Context
     * @return Information if system ad settings exist
     */
    @JvmStatic
    fun hasAdSettings(c: Context): Boolean {
        return isCallable(c, adSettings())
    }

    /**
     * Returns intent to write an email with an email app
     *
     * @param email   The recipient email address
     * @param subject The subject line
     * @param body    The email body
     * @return The intent
     */
    @JvmStatic
    fun emailIntent(email: String?, subject: String?, body: String?): Intent {
        val i = Intent(Intent.ACTION_SEND)
        i.setType("message/rfc822")
        if (email != null) {
            i.putExtra(Intent.EXTRA_EMAIL, arrayOf<String>(email))
        }
        i.putExtra(Intent.EXTRA_SUBJECT, subject)
        i.putExtra(Intent.EXTRA_TEXT, body)
        return i
    }

    /**
     * Checks if intent exists
     *
     * @param c      Content
     * @param intent Intent
     * @return Information if intent exists
     */
    @JvmStatic
    fun isCallable(c: Context, intent: Intent): Boolean {
        val list = c.getPackageManager().queryIntentActivities(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY
        )
        return list.size > 0
    }

    /**
     * Retrieves the name of the app based on the given uid
     *
     * @param uid - of the app
     * @return the name of the package of the app with the given uid, or "Unknown" if
     * no name could be found for the uid.
     */
    @JvmStatic
    fun getAppName(pm: PackageManager, uid: Int): String? {
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

        if (uid == 0) return "System"

        // If we can't find a running app, just get a list of packages that map to the uid
        val packages = pm.getPackagesForUid(uid)
        if (packages != null && packages.size > 0) return packages[0]

        return "Unknown"
    }

    /**
     * Returns launch intent for a provided app
     *
     * @param activity The current activity
     * @param appId    Package name of the app to be launched
     * @return An intent
     */
    @JvmStatic
    fun getLaunchIntent(activity: Activity, appId: String): Intent? {
        val intent = activity.getPackageManager().getLaunchIntentForPackage(appId)
        return if (intent == null ||
            intent.resolveActivity(activity.getPackageManager()) == null
        ) null else intent
    }

    /**
     * Computes a message in a snackbar to be shown to user.
     *
     * @param activity The current activity
     * @param msg      The message to be shown
     * @return The computed snackbar. Displayed with .show()
     */
    @JvmStatic
    fun getSnackbar(activity: Activity, msg: Int): Snackbar? {
        val v = activity.getWindow().getDecorView().findViewById<View?>(android.R.id.content)
        if (v == null) return null
        val s = Snackbar.make(
            v,
            msg,
            Snackbar.LENGTH_LONG
        )
        s.setActionTextColor(ContextCompat.getColor(activity, R.color.colorPrimary))
        return s
    }

    /**
     * Computes the current day of year
     *
     * @return The current day of year
     */
    @JvmStatic
    fun dayOfYear(): Int {
        val calendar = Calendar.getInstance()
        return calendar.get(Calendar.DAY_OF_YEAR)
    }

    /**
     * Casts a set of integers to set of strings
     *
     * @param ints Set of integers
     * @return Set of strings
     */
    @JvmStatic
    fun intToStringSet(ints: MutableSet<Int?>): MutableSet<String?> {
        val strings: MutableSet<String?> = HashSet<String?>()

        for (_int in ints) {
            strings.add(_int.toString())
        }

        return strings
    }

    @JvmStatic
    fun isNight(context: Context): Boolean {
        return (context.getResources()
            .getConfiguration().uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }
}
