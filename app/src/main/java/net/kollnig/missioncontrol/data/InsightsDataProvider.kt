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
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Pair
import androidx.preference.PreferenceManager
import eu.faircode.netguard.DatabaseHelper
import net.kollnig.missioncontrol.Common
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

        // Ensure TrackerList is initialized (loads trackers from assets if needed)
        TrackerList.getInstance(context)

        // Load filtering preferences
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val showSystem = prefs.getBoolean("show_system", false)
        val applyPrefs = context.getSharedPreferences("apply", Context.MODE_PRIVATE)
        val trackerProtectPrefs = context.getSharedPreferences("tracker_protect", Context.MODE_PRIVATE)
        val blockingMode = BlockingMode.getMode(context)

        // Cache for UID -> package info lookups
        val uidPackageCache = mutableMapOf<Int, String?>()
        val uidSystemCache = mutableMapOf<Int, Boolean>()
        val seenContacts = mutableSetOf<String>()

        // Maps for aggregation
        val appTrackerCounts = mutableMapOf<Int, Int>()  // uid -> unique tracker hosts
        val companyTrackerCounts = mutableMapOf<String, Int>() // company name -> total unique hosts
        val uniqueCompanies = mutableSetOf<String>()
        val appsWithTrackers = mutableSetOf<Int>()

        // Pervasive trackers: company -> set of UIDs that contacted it
        val companyToApps = mutableMapOf<String, MutableSet<Int>>()
        
        // Top domains: domain -> set of UIDs
        val domainToApps = mutableMapOf<String, MutableSet<Int>>()
        val uncertainDomains = mutableSetOf<String>()

        databaseHelper.getInsightsData7Days().use { cursor ->
            if (cursor != null && cursor.moveToFirst()) {
                val uidIndex = cursor.getColumnIndexOrThrow("uid")
                val daddrIndex = cursor.getColumnIndexOrThrow("daddr")
                val allowedIndex = cursor.getColumnIndex("allowed")
                val uncertainIndex = cursor.getColumnIndex("uncertain")

                do {
                    val uid = cursor.getInt(uidIndex)
                    val daddr = cursor.getString(daddrIndex)
                    val contactKey = "$uid|$daddr"

                    if (!seenContacts.add(contactKey)) continue

                    // Get package name (cached)
                    val packageName = uidPackageCache.getOrPut(uid) {
                        getPackageNameForUid(uid)
                    }

                    // Skip if we can't identify the package
                    if (packageName == null) continue

                    // Check if system app - skip if show_system is false
                    val isSystem = uidSystemCache.getOrPut(uid) {
                        isSystemApp(uid)
                    }
                    if (isSystem && !showSystem) continue

                    // Check if excluded from VPN
                    if (!applyPrefs.getBoolean(packageName, true)) continue

                    // Check if tracker protection is disabled for this app
                    if (!BlockingMode.isTrackerProtectionEnabled(context, trackerProtectPrefs, packageName)) continue

                    // Find tracker company for this hostname
                    val tracker = TrackerList.findTracker(daddr) ?: continue
                    val allowed = if (allowedIndex >= 0 && !cursor.isNull(allowedIndex))
                        cursor.getInt(allowedIndex)
                    else
                        -1
                    val uncertainty = if (uncertainIndex >= 0 && !cursor.isNull(uncertainIndex))
                        cursor.getInt(uncertainIndex)
                    else
                        DatabaseHelper.ACCESS_UNCERTAIN_NONE

                    val companyName = tracker.name ?: daddr
                    uniqueCompanies.add(companyName)
                    appsWithTrackers.add(uid)

                    // Count latest unique app-host contacts seen over the last 7 days.
                    data.totalTrackingAttempts += 1

                    val isBlocked = isTrackerContactBlocked(
                        uid,
                        tracker,
                        allowed,
                        uncertainty,
                        blockingMode
                    )
                    if (isBlocked) {
                        data.blockedTrackingAttempts += 1
                    } else {
                        data.allowedTrackingAttempts += 1
                    }

                    // Aggregate by app
                    appTrackerCounts.merge(uid, 1, Int::plus)

                    // Aggregate by company
                    companyTrackerCounts.merge(companyName, 1, Int::plus)

                    // Track which apps contact each company
                    companyToApps.getOrPut(companyName) { mutableSetOf() }.add(uid)

                    // Track which apps contact each domain
                    domainToApps.getOrPut(daddr) { mutableSetOf() }.add(uid)
                    if (uncertainty > DatabaseHelper.ACCESS_UNCERTAIN_NONE)
                        uncertainDomains.add(daddr)

                } while (cursor.moveToNext())
            }
        }

        data.uniqueTrackerCompanies = uniqueCompanies.size
        data.appsWithTrackers = appsWithTrackers.size

        // Build top tracking apps list
        val sortedApps = appTrackerCounts.entries
            .sortedByDescending { it.value }
            .take(5)

        data.topTrackingApps = sortedApps.mapNotNull { entry ->
            Common.getAppName(packageManager, entry.key)?.let { Pair(it, entry.value) }
        }.toMutableList()

        // Build top tracker companies list (by total hosts contacted)
        data.topTrackerCompanies = companyTrackerCounts.entries
            .sortedByDescending { it.value }
            .take(5)
            .map { Pair(it.key, it.value) }
            .toMutableList()

        // Build pervasive trackers list (by number of apps)
        data.pervasiveTrackers = companyToApps.entries
            .filter { it.value.size > 1 }  // Only include if in 2+ apps
            .sortedByDescending { it.value.size }
            .take(5)
            .map { Pair(it.key, it.value.size) }
            .toMutableList()

        // Build top domains list (by number of apps)
        // Group by TLD+1 (e.g., ads.google.com -> google.com) to reduce clutter
        // For uncertain entries, show alternate tracker domains inline
        val tldPlusOneToApps = mutableMapOf<String, MutableSet<Int>>()

        for ((daddr, uids) in domainToApps) {
            val tldPlusOne = extractTldPlusOne(daddr)
            
            // Build label - for uncertain domains, show alternates
            val label = if (uncertainDomains.contains(daddr)) {
                buildUncertainLabel(daddr, tldPlusOne)
            } else {
                tldPlusOne
            }
            
            tldPlusOneToApps.getOrPut(label) { mutableSetOf() }.addAll(uids)
        }
        
        data.topDomains = tldPlusOneToApps.entries
            .sortedByDescending { it.value.size }
            .take(20)
            .map { Pair(it.key, it.value.size) }
            .toMutableList()

        return data
    }

    /**
     * Get package name for a UID.
     */
    private fun getPackageNameForUid(uid: Int): String? {
        if (uid == 0) return "android"
        val packages = packageManager.getPackagesForUid(uid)
        return packages?.firstOrNull()
    }

    /**
     * Check if a UID belongs to a system app.
     */
    private fun isSystemApp(uid: Int): Boolean {
        if (uid == 0) return true
        val packages = packageManager.getPackagesForUid(uid) ?: return false
        return packages.any { pkg ->
            try {
                val appInfo = packageManager.getApplicationInfo(pkg, 0)
                (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    /**
     * Extract TLD+1 from a domain name (e.g., ads.google.com -> google.com)
     */
    private fun extractTldPlusOne(domain: String): String {
        val normalized = domain.trim('.').lowercase(Locale.ROOT)
        val parts = normalized.split(".").filter { it.isNotEmpty() }
        if (parts.size < 2)
            return normalized

        val suffix = parts.takeLast(2).joinToString(".")
        return if (parts.size >= 3 && MULTI_LABEL_PUBLIC_SUFFIXES.contains(suffix))
            "${parts[parts.size - 3]}.$suffix"
        else
            suffix
    }
    
    /**
     * Build a label for uncertain entries showing alternate tracker domains.
     * E.g., "google.com (or doubleclick.net)"
     */
    private fun buildUncertainLabel(daddr: String, tldPlusOne: String): String {
        val alternateTldPlusOnes = mutableSetOf<String>()
        
        // Query alternate domains that share the same IP
        databaseHelper.getAlternateQNames(daddr).use { altCursor ->
            if (altCursor != null && altCursor.moveToFirst()) {
                do {
                    val altDomain = altCursor.getString(0)
                    // Only include if it's a different TLD+1 and is a tracker
                    val altTldPlusOne = extractTldPlusOne(altDomain)
                    if (altTldPlusOne != tldPlusOne && TrackerList.findTracker(altDomain) != null) {
                        alternateTldPlusOnes.add(altTldPlusOne)
                    }
                } while (altCursor.moveToNext())
            }
        }
        
        return if (alternateTldPlusOnes.isNotEmpty()) {
            "$tldPlusOne (or ${alternateTldPlusOnes.sorted().take(2).joinToString(", ")})"
        } else {
            tldPlusOne
        }
    }

    private fun isTrackerContactBlocked(
        uid: Int,
        tracker: Tracker,
        allowed: Int,
        uncertainty: Int,
        blockingMode: String
    ): Boolean {
        if (allowed >= 0)
            return allowed == 0

        if (!BlockingMode.isStrictMode(context)
            && uncertainty == DatabaseHelper.ACCESS_UNCERTAIN_MIXED_TRACKER_AND_NON_TRACKER) {
            return false
        }

        val blockedByGranularRule = if (BlockingMode.MODE_MINIMAL == blockingMode) {
            false
        } else {
            trackerBlocklist.blockedTracker(uid, tracker)
        }

        return BlockingModeLogic.shouldBlockKnownTracker(
            blockingMode,
            tracker.category,
            blockedByGranularRule
        )
    }

    companion object {
        private val MULTI_LABEL_PUBLIC_SUFFIXES = setOf(
            "ac.uk", "co.uk", "gov.uk", "org.uk",
            "co.jp", "ne.jp", "or.jp",
            "com.au", "edu.au", "gov.au", "net.au", "org.au",
            "ac.nz", "co.nz", "govt.nz", "org.nz",
            "co.in", "firm.in", "gen.in", "ind.in", "net.in", "org.in",
            "com.br", "com.cn", "com.hk", "com.mx", "com.my", "com.sg",
            "com.tr", "com.tw", "net.cn", "org.cn"
        )
    }
}
