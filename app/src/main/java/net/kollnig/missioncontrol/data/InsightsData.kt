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
 * Contains 7-day statistics about trackers contacted and blocking effectiveness.
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

    // Pervasive trackers - companies found in multiple apps (company name, app count)
    var pervasiveTrackers: MutableList<Pair<String, Int>> = mutableListOf(),

    // Top domains contacted (domain, number of apps)
    var topDomains: MutableList<Pair<String, Int>> = mutableListOf()
) {
    /**
     * Calculate the blocking percentage.
     * @return Percentage of trackers that were blocked (0-100)
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

