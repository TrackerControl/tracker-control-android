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
import eu.faircode.netguard.Util
import net.kollnig.missioncontrol.Common
import net.kollnig.missioncontrol.R
import java.util.Locale

/** One observed TLD+1 domain and the apps that contacted it. */
internal data class DomainObservation(
    val aliases: Set<String>,
    val appUids: Set<Int>
)

/** A deterministic domain row for the Insights screen. */
internal data class AggregatedDomain(
    val label: String,
    val appCount: Int
)

/** How many aliases a single domain label may name before it is truncated. */
private const val MAX_LABEL_ALIASES = 2

/** Merge observations with exactly the same alias set, counting each UID once. */
internal fun aggregateDomainObservations(
    observations: Iterable<DomainObservation>,
    limit: Int = 20
): List<AggregatedDomain> {
    if (limit <= 0) return emptyList()

    val groups = linkedMapOf<Set<String>, MutableSet<Int>>()
    observations.forEach { observation ->
        val aliases = observation.aliases
            .filter { it.isNotBlank() }
            .toSet()
        if (aliases.isEmpty()) return@forEach

        groups.getOrPut(aliases) { mutableSetOf() }.addAll(observation.appUids)
    }

    return groups
        .map { (aliases, appUids) ->
            AggregatedDomain(
                // Cap the joined label at two aliases: the Insights row is a
                // single line, and a longer join simply overflows it.
                label = aliases.sorted().take(MAX_LABEL_ALIASES).joinToString(" or "),
                appCount = appUids.size
            )
        }
        .sortedWith(compareByDescending<AggregatedDomain> { it.appCount }.thenBy { it.label })
        .take(limit)
}

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
        val minimalOnlyPrefs = context.getSharedPreferences("tracker_essential", Context.MODE_PRIVATE)
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

                    // Get package name (cached; null is a valid cached result,
                    // so probe with containsKey to avoid re-resolving every row)
                    val packageName = if (uidPackageCache.containsKey(uid))
                        uidPackageCache[uid]
                    else
                        getPackageNameForUid(uid).also { uidPackageCache[uid] = it }

                    // A UID with no resolvable package (other profile, cloned or
                    // uninstalled app) is still recorded by ServiceSinkhole,
                    // which defaults unknown UIDs to tracked. Count it here too
                    // instead of dropping it; the per-package preference checks
                    // below simply have nothing to look up.
                    if (packageName != null) {
                        // Check if system app - skip if show_system is false
                        val isSystem = uidSystemCache.getOrPut(uid) {
                            isSystemApp(uid)
                        }
                        if (isSystem && !showSystem) continue

                        // Check if excluded from VPN
                        if (!applyPrefs.getBoolean(packageName, true)) continue

                        // Check if tracker protection is disabled for this app
                        if (!BlockingMode.isTrackerProtectionEnabled(context, trackerProtectPrefs, packageName)) continue
                    }

                    val minimalOnly = packageName != null
                        && BlockingMode.isMinimalOnlyApp(context, minimalOnlyPrefs, packageName)

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
                        daddr,
                        tracker,
                        allowed,
                        uncertainty,
                        blockingMode,
                        minimalOnly
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

        data.topTrackingApps = sortedApps.map { entry ->
            val uid = entry.key
            // Unresolvable UIDs are now counted, so name them by UID rather
            // than letting them all collapse into a bare "Unknown" row.
            val name = if (uid != 0 && uidPackageCache[uid] == null)
                context.getString(R.string.unidentified_app_uid, uid)
            else
                Common.getAppName(packageManager, uid)
                    ?: context.getString(R.string.unidentified_app_uid, uid)
            Pair(name, entry.value)
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

        // Build top domains list (by number of apps), grouping only identical
        // uncertain alias sets while keeping distinct ambiguity sets separate.
        val domainObservations = domainToApps.map { (daddr, uids) ->
            val primary = extractTldPlusOne(daddr)
            val aliases = mutableSetOf(primary)
            if (uncertainDomains.contains(daddr)) {
                aliases += getTrackerAlternateTldPlusOnes(daddr, primary)
            }
            DomainObservation(aliases = aliases, appUids = uids)
        }

        data.topDomains = aggregateDomainObservations(domainObservations)
            .map { Pair(it.label, it.appCount) }
            .toMutableList()

        return data
    }

    /**
     * Get package name for a UID.
     */
    private fun getPackageNameForUid(uid: Int): String? {
        if (uid == 0) return "android"
        val packages = Util.getPackagesForUid(packageManager, uid)
        return packages?.firstOrNull()
    }

    /**
     * Check if a UID belongs to a system app.
     */
    private fun isSystemApp(uid: Int): Boolean {
        if (uid == 0) return true
        val packages = Util.getPackagesForUid(packageManager, uid) ?: return false
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
    
    /** Return tracker alternate TLD+1 values for an uncertain observation. */
    private fun getTrackerAlternateTldPlusOnes(
        daddr: String,
        primary: String
    ): Set<String> {
        val alternateTldPlusOnes = mutableSetOf<String>()
        databaseHelper.getAlternateQNames(daddr).use { altCursor ->
            if (altCursor != null && altCursor.moveToFirst()) {
                do {
                    val altDomain = altCursor.getString(0)
                    val altTldPlusOne = extractTldPlusOne(altDomain)
                    if (altTldPlusOne != primary && TrackerList.findTracker(altDomain) != null) {
                        alternateTldPlusOnes.add(altTldPlusOne)
                    }
                } while (altCursor.moveToNext())
            }
        }
        return alternateTldPlusOnes
    }

    private fun isTrackerContactBlocked(
        uid: Int,
        daddr: String,
        tracker: Tracker,
        allowed: Int,
        uncertainty: Int,
        blockingMode: String,
        minimalOnly: Boolean
    ): Boolean {
        if (allowed >= 0)
            return allowed == 0

        if (blockingMode != BlockingMode.MODE_MINIMAL && minimalOnly) {
            val minimalTracker = TrackerList.findMinimalTracker(daddr)
            return BlockingModeLogic.shouldBlockMinimalOnly(minimalTracker?.category)
        }

        if (!BlockingMode.isStrictMode(context)
            && uncertainty == DatabaseHelper.ACCESS_UNCERTAIN_MIXED_TRACKER_AND_NON_TRACKER) {
            return false
        }

        if (BlockingMode.MODE_MINIMAL == blockingMode) {
            // Minimal mode detects with every list but blocks only the DDG set.
            val minimalTracker = TrackerList.findMinimalTracker(daddr)
            return BlockingModeLogic.shouldBlockMinimalOnly(minimalTracker?.category)
        }

        return trackerBlocklist.blockedTracker(uid, tracker)
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
