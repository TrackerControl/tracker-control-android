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

package net.kollnig.missioncontrol.data

import android.content.Context
import android.content.pm.PackageManager
import android.util.Pair
import eu.faircode.netguard.DatabaseHelper
import net.kollnig.missioncontrol.Common
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Provider class that computes InsightsData from the database.
 * Aggregates tracking statistics for the past 7 days.
 */
class InsightsDataProvider(context: Context) {

    private val context: Context = context.applicationContext
    private val databaseHelper: DatabaseHelper = DatabaseHelper.getInstance(context)
    private val packageManager: PackageManager = context.packageManager
    private val trackerBlocklist: TrackerBlocklist = TrackerBlocklist.getInstance(context)

    /**
     * Compute insights data for the past 7 days.
     * This is a potentially expensive operation - call from background thread.
     *
     * @return InsightsData with all statistics populated
     */
    fun computeInsights(): InsightsData {
        val data = InsightsData()

        // Maps for aggregation
        val appConnectionCounts = mutableMapOf<Int, Int>()  // uid -> total connections
        val companyConnectionCounts = mutableMapOf<String, Int>() // company name -> total connections
        val uniqueCompanies = mutableSetOf<String>()
        val appsWithTrackers = mutableSetOf<Int>()

        // Daily breakdown
        val dailyTotal = linkedMapOf<String, Int>()
        val dailyBlocked = linkedMapOf<String, Int>()

        // Initialize daily maps for the past 7 days
        val dayFormat = SimpleDateFormat("EEE", Locale.getDefault())
        val now = System.currentTimeMillis()
        for (i in 6 downTo 0) {
            val dayLabel = dayFormat.format(Date(now - i * 24L * 60 * 60 * 1000))
            dailyTotal[dayLabel] = 0
            dailyBlocked[dayLabel] = 0
        }

        databaseHelper.getInsightsData7Days().use { cursor ->
            if (cursor != null && cursor.moveToFirst()) {
                val uidIndex = cursor.getColumnIndexOrThrow("uid")
                val daddrIndex = cursor.getColumnIndexOrThrow("daddr")
                val connectionsIndex = cursor.getColumnIndexOrThrow("connections")
                val timeIndex = cursor.getColumnIndexOrThrow("time")

                do {
                    val uid = cursor.getInt(uidIndex)
                    val daddr = cursor.getString(daddrIndex)
                    val connections = if (cursor.isNull(connectionsIndex)) 1 else cursor.getInt(connectionsIndex)
                    val time = cursor.getLong(timeIndex)

                    // Find tracker company for this hostname
                    val tracker = TrackerList.findTracker(daddr) ?: continue

                    val companyName = tracker.name ?: daddr
                    uniqueCompanies.add(companyName)
                    appsWithTrackers.add(uid)

                    // Check if this tracker is currently blocked using TrackerBlocklist rules
                    val isBlocked = trackerBlocklist.blockedTracker(uid, tracker)

                    // Count total and blocked
                    data.totalTrackingAttempts += connections
                    if (isBlocked) {
                        data.blockedTrackingAttempts += connections
                    } else {
                        data.allowedTrackingAttempts += connections
                    }

                    // Aggregate by app
                    appConnectionCounts.merge(uid, connections, Int::plus)

                    // Aggregate by company
                    companyConnectionCounts.merge(companyName, connections, Int::plus)

                    // Daily breakdown
                    val dayLabel = dayFormat.format(Date(time))
                    if (dailyTotal.containsKey(dayLabel)) {
                        dailyTotal.merge(dayLabel, connections, Int::plus)
                        if (isBlocked) {
                            dailyBlocked.merge(dayLabel, connections, Int::plus)
                        }
                    }
                } while (cursor.moveToNext())
            }
        }

        data.uniqueTrackerCompanies = uniqueCompanies.size
        data.appsWithTrackers = appsWithTrackers.size
        data.trackingByDay = dailyTotal
        data.blockedByDay = dailyBlocked

        // Build top tracking apps list
        val sortedApps = appConnectionCounts.entries
            .sortedByDescending { it.value }
            .take(5)

        data.topTrackingApps = sortedApps.mapNotNull { entry ->
            Common.getAppName(packageManager, entry.key)?.let { Pair(it, entry.value) }
        }.toMutableList()

        // Build top tracker companies list
        data.topTrackerCompanies = companyConnectionCounts.entries
            .sortedByDescending { it.value }
            .take(5)
            .map { Pair(it.key, it.value) }
            .toMutableList()

        return data
    }
}

