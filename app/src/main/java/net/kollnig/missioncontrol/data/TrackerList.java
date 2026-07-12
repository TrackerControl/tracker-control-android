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

package net.kollnig.missioncontrol.data;

import static net.kollnig.missioncontrol.data.TrackerCategory.UNCATEGORISED;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.collection.ArrayMap;
import androidx.preference.PreferenceManager;

import eu.faircode.netguard.DatabaseHelper;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import eu.faircode.netguard.DatabaseHelper;
import eu.faircode.netguard.ServiceSinkhole;

/**
 * Loads and stores the database of known tracker companies and their domains
 */
public class TrackerList {
    private static final String TAG = TrackerList.class.getSimpleName();
    // Shared CDN registrable domains that host legitimate content for many
    // sites and therefore cause false positives when a tracker shares the same
    // infrastructure. Matched exactly against the domains listed in the
    // blocklists. Note: Cloudflare's own analytics beacon
    // (cloudflareinsights.com) is deliberately NOT ignored here so it stays
    // blockable — only the general Cloudflare CDN domain (which fronts e.g.
    // cdnjs.cloudflare.com) is whitelisted.
    private static final Set<String> ignoreDomains = new HashSet<>(Arrays.asList(
            "cloudfront.net",
            "fastly.net",
            "cloudflare.com"));
    private static final Map<String, Tracker> hostnameToTracker = new ConcurrentHashMap<>();
    public static String TRACKER_HOSTLIST = "TRACKER_HOSTLIST";
    private static final Tracker hostlistTracker = new Tracker(TRACKER_HOSTLIST, UNCATEGORISED);
    private static TrackerList instance;
    private static boolean domainBasedBlocking;
    private static boolean minimalBlockingMode;
    private final DatabaseHelper databaseHelper;
    
    // Lock for synchronizing tracker data reload operations
    private static final Object reloadLock = new Object();

    // Performance: Cache tracker counts to avoid full DB scans on every refresh
    private Pair<Pair<Map<Integer, Integer>, Integer>, Pair<Map<Integer, Integer>, Integer>> cachedTrackerCounts;
    private long lastTrackerCountComputeTime = 0;
    private static final long TRACKER_COUNT_CACHE_TTL_MS = 2000; // 2 second cache

    private TrackerList(Context c) {
        databaseHelper = DatabaseHelper.getInstance(c);
        loadTrackers(c);
    }

    /**
     * Get an instance of the tracker database
     *
     * @param c Context
     * @return Instance of the tracker database
     */
    public static TrackerList getInstance(Context c) {
        if (instance == null)
            instance = new TrackerList(c);

        return instance;
    }

    static boolean isIgnoredDomain(String domain) {
        return ignoreDomains.contains(domain);
    }

    /**
     * Identifies tracker hosts
     *
     * @param hostname A hostname of interest
     * @return A {@link Tracker} object, if host is null, null otherwise
     */
    public static Tracker findTracker(@NonNull String hostname) {
        // DNS is case-insensitive, but the tracker/hosts lists are keyed in
        // lowercase and qnames are stored as they appear on the wire. Without
        // normalising here, a query for Graph.Facebook.Com — whether a
        // deliberate evasion or a resolver using 0x20 case randomisation —
        // would slip past detection and blocking.
        hostname = hostname.toLowerCase(Locale.ROOT);

        Tracker t = null;

        if (hostnameToTracker.containsKey(hostname)) {
            t = hostnameToTracker.get(hostname);
        } else { // check subdomains
            for (int i = 0; i < hostname.length(); i++) {
                if (hostname.charAt(i) == '.') {
                    t = hostnameToTracker.get(hostname.substring(i + 1));
                    if (t != null)
                        break;
                }
            }
        }

        // In minimal mode, skip hosts-file based lookups (only use DDG tracker list)
        if (t == null && !minimalBlockingMode
                && ServiceSinkhole.mapHostsBlocked.containsKey(hostname))
            if (domainBasedBlocking)
                return hostlistTracker;
            else {
                t = new Tracker(hostname, UNCATEGORISED);
                hostnameToTracker.put(hostname, t);
                return t;
            }

        return t;
    }

    /**
     * Reload tracker data by clearing all existing data and reloading from assets.
     * This should be called when the hosts blocklist is updated to ensure TrackerList
     * stays in sync with the updated hosts.
     *
     * @param c Context
     */
    public static void reloadTrackerData(Context c) {
        Log.i(TAG, "Reloading tracker data");
        
        // Synchronize the entire reload operation to prevent race conditions
        synchronized (reloadLock) {
            // Clear existing data (both are thread-safe collections)
            hostnameToTracker.clear();
            
            // Ensure instance exists and reload trackers from assets
            TrackerList trackerList = getInstance(c);
            trackerList.loadTrackers(c);
            // Invalidate cached tracker counts since tracker data has changed
            trackerList.invalidateTrackerCountCache();
        }
    }

    /**
     * Load tracker domain database
     *
     * @param c Context
     */
    public void loadTrackers(Context c) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(c);
        domainBasedBlocking = prefs.getBoolean("domain_based_blocking",
                prefs.getBoolean("domain_based_blocked", false));
        minimalBlockingMode = BlockingMode.isMinimalMode(c);

        if (minimalBlockingMode) {
            // In minimal mode, only load DDG trackers (skip X-Ray and Disconnect)
            // This ensures only confirmed, breakage-tested trackers are blocked
            loadDuckDuckGoTrackers(c);
        } else {
            loadXrayTrackers(c);
            loadDisconnectTrackers(c); // loaded last to overwrite X-Ray hosts with extra category information
            loadDuckDuckGoTrackers(c); // DuckDuckGo tracker list for additional mobile-specific trackers
        }
    }

    /**
     * Retrieves information about number of contacted tracking companies, for all
     * apps
     *
     * @return Number of contacted tracking companies, for all apps
     */
    public synchronized Pair<Pair<Map<Integer, Integer>, Integer>, Pair<Map<Integer, Integer>, Integer>> getTrackerCountsAndTotal() {
        // Performance: Return cached result if still valid
        long now = System.currentTimeMillis();
        if (cachedTrackerCounts != null && (now - lastTrackerCountComputeTime) < TRACKER_COUNT_CACHE_TTL_MS) {
            return cachedTrackerCounts;
        }

        Map<Integer, Set<String>> trackers = new ArrayMap<>();
        Map<Integer, Set<String>> trackersWeek = new ArrayMap<>();

        try (Cursor cursor = databaseHelper.getHosts()) {
            long limit = new Date().getTime() - 7 * 24 * 3600 * 1000L;
            if (cursor.moveToFirst()) {
                do {
                    int appUid = cursor.getInt(cursor.getColumnIndexOrThrow("uid"));
                    String hostname = cursor.getString(cursor.getColumnIndexOrThrow("daddr"));
                    Tracker tracker = findTracker(hostname);
                    checkTracker(trackers, appUid, tracker);

                    long time = cursor.getLong(cursor.getColumnIndexOrThrow("time"));
                    if (time > limit)
                        checkTracker(trackersWeek, appUid, tracker);
                } while (cursor.moveToNext());
            }
        }

        cachedTrackerCounts = new Pair<>(countTrackers(trackers), countTrackers(trackersWeek));
        lastTrackerCountComputeTime = now;
        return cachedTrackerCounts;
    }

    /**
     * Invalidate the cached tracker counts. Should be called when host data
     * changes.
     */
    public synchronized void invalidateTrackerCountCache() {
        cachedTrackerCounts = null;
    }

    /**
     * Retrieves the set of contacted tracker company names, per app UID (#447), so
     * the app-list search can filter apps by "tracker:<company>". A single DB scan
     * builds a lookup for every app at once, instead of querying per row; the
     * caller (AdapterRule's search Filter) already runs on a background thread, so
     * no caching is needed here.
     *
     * @return Map of app UID to the set of tracker company names observed for it
     */
    public synchronized Map<Integer, Set<String>> getTrackerNamesByUid() {
        Map<Integer, Set<String>> trackers = new ArrayMap<>();

        try (Cursor cursor = databaseHelper.getHosts()) {
            if (cursor.moveToFirst()) {
                do {
                    int appUid = cursor.getInt(cursor.getColumnIndexOrThrow("uid"));
                    String hostname = cursor.getString(cursor.getColumnIndexOrThrow("daddr"));
                    Tracker tracker = findTracker(hostname);
                    checkTracker(trackers, appUid, tracker);
                } while (cursor.moveToNext());
            }
        }

        return trackers;
    }

    /**
     * Helper method to check tracker
     *
     * @param trackers Set of seen trackers
     * @param appUid   Uid of app
     * @param tracker  Seen tracker
     */
    private void checkTracker(Map<Integer, Set<String>> trackers, int appUid, Tracker tracker) {
        Set<String> observedTrackers = trackers.get(appUid);
        if (observedTrackers == null) {
            observedTrackers = new HashSet<>();
            trackers.put(appUid, observedTrackers);
        }

        if (tracker != null)
            observedTrackers.add(tracker.getName());
    }

    /**
     * Count the number of seen trackers
     *
     * @param trackers Set of seen trackers
     * @return
     */
    @NonNull
    private Pair<Map<Integer, Integer>, Integer> countTrackers(Map<Integer, Set<String>> trackers) {
        int totalTracker = 0;
        Map<Integer, Integer> trackerCounts = new ArrayMap<>();
        for (Map.Entry<Integer, Set<String>> entry : trackers.entrySet()) {
            trackerCounts.put(entry.getKey(), entry.getValue().size());
            totalTracker += entry.getValue().size();
        }

        return new Pair<>(trackerCounts, totalTracker);
    }

    /**
     * Iterate through all recorded host contacts and identify those that belong to known trackers,
     * For each app, only the most recent tracker contact time is stored in the hashmap. Used to
     * sort by most recent tracker detection in the main app list.
     *
     * @return A map of app UIDs to their most recent tracker contact timestamp in milliseconds
     * since epoch. Apps with no tracker contacts are not included in the map.
     */
    public synchronized Map<Integer, Long> getLastTrackerTimes() {
        Map<Integer, Long> result = new HashMap<>();

        try (Cursor cursor = databaseHelper.getHosts()) {
            if (cursor.moveToFirst()) {
                do {
                    int uid = cursor.getInt(cursor.getColumnIndexOrThrow("uid"));
                    String hostname = cursor.getString(cursor.getColumnIndexOrThrow("daddr"));
                    long time = cursor.getLong(cursor.getColumnIndexOrThrow("time"));

                    Tracker tracker = findTracker(hostname);
                    if (tracker == null)
                        continue;

                    Long existing = result.get(uid);
                    if (existing == null || time > existing)
                        result.put(uid, time);

                } while (cursor.moveToNext());
            }
        }

        return result;
    }
    /**
     * Retrieve info for CSV export
     *
     * @return All logged communications about app
     */
    public Cursor getAppInfo(int uid) {
        return databaseHelper.getHosts(uid);
    }

    /**
     * Retrieves information about all seen trackers
     *
     * @return A list of seen trackers
     */
    public synchronized List<TrackerCategory> getAppTrackers(Context c, int uid) {
        Map<String, TrackerCategory> categoryToTracker = new ArrayMap<>();

        try (Cursor cursor = databaseHelper.getHosts(uid)) {
            if (cursor.moveToFirst()) {
                outer: do {
                    String host = cursor.getString(cursor.getColumnIndexOrThrow("daddr"));
                    long lastSeen = cursor.getLong(cursor.getColumnIndexOrThrow("time"));
                    int uncertainty = cursor.getInt(cursor.getColumnIndexOrThrow("uncertain"));
                    boolean uncertain = uncertainty >= DatabaseHelper.ACCESS_UNCERTAIN_MIXED_TRACKER_AND_NON_TRACKER;
                    boolean allowedInStandardMode =
                            uncertainty == DatabaseHelper.ACCESS_UNCERTAIN_MIXED_TRACKER_AND_NON_TRACKER;

                    Tracker tracker = findTracker(host);
                    if (tracker == null)
                        continue;

                    // Tracker constructor canonicalises both, but normalise
                    // defensively in case stored objects pre-date that change.
                    String category = TrackerCategory.canonicalise(tracker.category);
                    String name = tracker.getName();

                    TrackerCategory categoryCompany = categoryToTracker.get(category);
                    if (categoryCompany == null) {
                        categoryCompany = new TrackerCategory(category, lastSeen);
                        categoryToTracker.put(category, categoryCompany);
                    } else {
                        if (categoryCompany.lastSeen < lastSeen)
                            categoryCompany.lastSeen = lastSeen;
                    }

                    if (uncertain) {
                        host = host + " *";
                        categoryCompany.setUncertain(true);
                    }

                    // check if tracker has already been added
                    for (Tracker child : categoryCompany.getChildren()) {
                        if (child.name != null
                                && child.name.equals(name)) {
                            child.addHost(host);
                            if (uncertain)
                                child.setUncertain(true);
                            if (allowedInStandardMode)
                                child.setAllowedInStandardMode(true);

                            if (child.lastSeen < lastSeen)
                                child.lastSeen = lastSeen;

                            continue outer;
                        }
                    }

                    Tracker child = new Tracker(name, category, lastSeen);
                    child.addHost(host);
                    child.setUncertain(uncertain);
                    child.setAllowedInStandardMode(allowedInStandardMode);
                    categoryCompany.getChildren().add(child);
                } while (cursor.moveToNext());
            }
        }

        // map to list
        List<TrackerCategory> trackerCategoryList = new ArrayList<>(categoryToTracker.values());

        // sort lists
        Collections.sort(trackerCategoryList, (o1, o2) -> o1.getDisplayName(c).compareTo(o2.getDisplayName(c)));
        for (TrackerCategory child : trackerCategoryList)
            Collections.sort(child.getChildren(), (o1, o2) -> o2.lastSeen.compareTo(o1.lastSeen));

        return trackerCategoryList;
    }

    /**
     * Loads X-Ray tracker list
     *
     * @param c Context
     */
    private void loadXrayTrackers(Context c) {
        // Keep track of parent companies
        Map<String, Tracker> rootParents = new HashMap<>();

        // Stream-parse to avoid materialising the whole file as a String plus a
        // JSONArray DOM (three transient copies). Produces the exact same map as
        // the previous DOM-based parse. See loadTrackers() footprint notes (#405).
        try (InputStream is = c.getAssets().open("xray-blacklist.json");
             JsonReader reader = new JsonReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            // Each JSON array entry contains a tracker company with its domains
            reader.beginArray();
            while (reader.hasNext()) {
                // Field order is not guaranteed and the resolved company name
                // (possibly root_parent) must be known before domains are
                // attached, so collect the fields we need first.
                String ownerName = null;
                String rootParent = null;
                String country = null;
                boolean necessary = false;
                List<String> doms = new ArrayList<>();

                reader.beginObject();
                while (reader.hasNext()) {
                    switch (reader.nextName()) {
                        case "owner_name":
                            ownerName = reader.nextString();
                            break;
                        case "root_parent":
                            // Mirror JSONObject.isNull(): an explicit null (or an
                            // absent key) leaves rootParent null and is ignored.
                            if (reader.peek() == JsonToken.NULL)
                                reader.nextNull();
                            else
                                rootParent = reader.nextString();
                            break;
                        case "necessary":
                            necessary = reader.nextBoolean();
                            break;
                        case "country":
                            country = reader.nextString();
                            break;
                        case "doms":
                            reader.beginArray();
                            while (reader.hasNext())
                                doms.add(reader.nextString());
                            reader.endArray();
                            break;
                        default:
                            reader.skipValue();
                            break;
                    }
                }
                reader.endObject();

                String name = ownerName;
                // Necessary tracker are identified at lowest company level
                if (rootParent != null && !necessary)
                    name = rootParent;

                // Check if we've seen a tracker from the same root company
                Tracker tracker = rootParents.get(name);
                if (tracker == null) {
                    String category = necessary ? "Content" : UNCATEGORISED;
                    tracker = new Tracker(name, category);
                    tracker.country = country;
                    rootParents.put(name, tracker);
                }

                // Add domains to tracker map
                for (String dom : doms) {
                    if (isIgnoredDomain(dom))
                        continue;

                    addTrackerDomain(tracker, dom);
                }
            }
            reader.endArray();
        } catch (IOException | IllegalStateException e) {
            Log.e(TAG, "Loading X-Ray list failed.. ", e);
        }
    }

    /**
     * Load DuckDuckGo tracker list
     *
     * @param c Context
     */
    private void loadDuckDuckGoTrackers(Context c) {
        // Stream-parse to avoid materialising the whole file as a String plus a
        // JSONObject DOM. Produces the exact same map as the previous DOM-based
        // parse (this list is loaded in every mode, so it also helps minimal mode).
        try (InputStream is = c.getAssets().open("duckduckgo-android-tds.json");
             JsonReader reader = new JsonReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            reader.beginObject();
            while (reader.hasNext()) {
                if (!"trackers".equals(reader.nextName())) {
                    reader.skipValue();
                    continue;
                }

                // Iterate through all tracker domains
                reader.beginObject();
                while (reader.hasNext()) {
                    String domain = reader.nextName();

                    // We only need the owner's display name and the default action
                    // from each entry; everything else is skipped.
                    String displayName = null;
                    String defaultAction = null;

                    reader.beginObject();
                    while (reader.hasNext()) {
                        switch (reader.nextName()) {
                            case "owner":
                                reader.beginObject();
                                while (reader.hasNext()) {
                                    if ("displayName".equals(reader.nextName()))
                                        displayName = reader.nextString();
                                    else
                                        reader.skipValue();
                                }
                                reader.endObject();
                                break;
                            case "default":
                                defaultAction = reader.nextString();
                                break;
                            default:
                                reader.skipValue();
                                break;
                        }
                    }
                    reader.endObject();

                    // Skip CDN domains that would cause false positives
                    if (isIgnoredDomain(domain))
                        continue;

                    // Check if tracker already exists (e.g., from Disconnect list)
                    Tracker existingTracker = hostnameToTracker.get(domain);

                    // Only add/overwrite if:
                    // 1. Tracker doesn't exist yet, OR
                    // 2. Existing tracker has "Content" category (which can be overwritten)
                    if (existingTracker != null && !"Content".equals(existingTracker.category)) {
                        // Don't overwrite non-Content categories from Disconnect
                        continue;
                    }

                    // Determine category based on default action
                    String category;
                    if ("ignore".equals(defaultAction)) {
                        category = "Content"; // Similar to necessary trackers
                    } else {
                        category = UNCATEGORISED; // Default to uncategorised instead of Advertisement
                    }

                    // Create tracker with owner's display name
                    Tracker tracker = new Tracker(displayName, category);

                    // Add domain to tracker map
                    addTrackerDomain(tracker, domain);
                }
                reader.endObject();
            }
            reader.endObject();
        } catch (IOException | IllegalStateException e) {
            Log.e(TAG, "Loading DuckDuckGo list failed.. ", e);
        }
    }

    /**
     * Load Disconnect.me tracker list
     *
     * @param c Context
     */
    private void loadDisconnectTrackers(Context c) {
        /*
         * Read domain list:
         *
         * File is a reversed string, because some anti-virus scanners found the list
         * suspicious
         * More here:
         * https://github.com/TrackerControl/tracker-control-android/issues/30
         */
        try (InputStream is = c.getAssets().open("disconnect-blacklist.reversed.json")) {
            int size = is.available();
            byte[] buffer = new byte[size];
            if (is.read(buffer) <= 0)
                throw new IOException("No bytes read.");

            String reversedJson = new String(buffer, StandardCharsets.UTF_8);
            String json = new StringBuilder(reversedJson).reverse().toString();

            // Stream-parse the (un-reversed) JSON instead of building a full
            // JSONObject DOM on top of the two String copies already required by
            // the reversal. Traversal mirrors the previous DOM walk exactly.
            try (JsonReader reader = new JsonReader(new StringReader(json))) {
                reader.beginObject();
                while (reader.hasNext()) {
                    if (!"categories".equals(reader.nextName())) {
                        reader.skipValue();
                        continue;
                    }

                    reader.beginObject();
                    while (reader.hasNext()) {
                        String categoryName = reader.nextName();

                        // Map Disconnect's category names onto our UI buckets. The
                        // mapping lives in TrackerCategory so it is a single source
                        // of truth that DisconnectCategoryCoverageTest can verify.
                        String canonicalCategory = TrackerCategory.mapDisconnectCategory(categoryName);

                        reader.beginArray();
                        while (reader.hasNext()) {
                            // Each element is a single-key object:
                            // { trackerName: { homeUrl: [dom, ...], ... } }
                            reader.beginObject();
                            // Only the first key (the tracker name) is used, matching
                            // the previous jsonTracker.keys().next() behaviour; any
                            // further keys are skipped.
                            boolean trackerConsumed = false;
                            while (reader.hasNext()) {
                                String trackerName = reader.nextName();
                                if (trackerConsumed) {
                                    reader.skipValue();
                                    continue;
                                }
                                trackerConsumed = true;

                                Tracker tracker = new Tracker(trackerName, canonicalCategory);

                                // Parse tracker domains
                                reader.beginObject();
                                while (reader.hasNext()) {
                                    reader.nextName(); // tracker home URL (unused)

                                    // Skip non-domain fields (mirrors the previous
                                    // instanceof JSONArray check).
                                    if (reader.peek() != JsonToken.BEGIN_ARRAY) {
                                        reader.skipValue();
                                        continue;
                                    }

                                    reader.beginArray();
                                    while (reader.hasNext()) {
                                        String dom = reader.nextString();

                                        if (isIgnoredDomain(dom))
                                            continue;

                                        addTrackerDomain(tracker, dom);
                                    }
                                    reader.endArray();
                                }
                                reader.endObject();
                            }
                            reader.endObject();
                        }
                        reader.endArray();
                    }
                    reader.endObject();
                }
                reader.endObject();
            }
        } catch (IOException | IllegalStateException e) {
            Log.e(TAG, "Loading Disconnect.me list failed.. ", e);
        }
    }

    /**
     * Internal method to add tracker to the tracker database that is used at
     * runtime
     *
     * @param tracker Tracker to be added
     * @param dom     Domain to be added
     */
    private void addTrackerDomain(Tracker tracker, String dom) {
        if (domainBasedBlocking) {
            Tracker t = new Tracker(dom + " (" + tracker.getName() + ")", tracker.category);
            t.country = tracker.country;
            hostnameToTracker.put(dom, t);
        } else
            hostnameToTracker.put(dom, tracker);
    }

}
