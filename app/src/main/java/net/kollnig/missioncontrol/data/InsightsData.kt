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

import android.util.Pair

/**
 * Data model holding aggregated tracking statistics for the Insights screen.
 * Contains 7-day statistics about tracking attempts, blocks, and top offenders.
 */
data class InsightsData(
    // Overall 7-day summary
    var totalTrackingAttempts: Int = 0,
    var blockedTrackingAttempts: Int = 0,
    var allowedTrackingAttempts: Int = 0,
    var uniqueTrackerCompanies: Int = 0,
    var appsWithTrackers: Int = 0,

    // Top offenders (sorted by count descending)
    var topTrackingApps: MutableList<Pair<String, Int>> = mutableListOf(),
    var topTrackerCompanies: MutableList<Pair<String, Int>> = mutableListOf(),

    // Daily breakdown (ordered by date)
    var trackingByDay: MutableMap<String, Int> = mutableMapOf(),
    var blockedByDay: MutableMap<String, Int> = mutableMapOf()
) {
    /**
     * Calculate the blocking percentage.
     * @return Percentage of tracking attempts that were blocked (0-100)
     */
    fun getBlockedPercentage(): Int {
        if (totalTrackingAttempts == 0) return 0
        return Math.round((blockedTrackingAttempts * 100f) / totalTrackingAttempts)
    }

    /**
     * Check if there is any tracking data to display.
     * @return true if there is data, false otherwise
     */
    fun hasData(): Boolean {
        return totalTrackingAttempts > 0 || uniqueTrackerCompanies > 0
    }
}
