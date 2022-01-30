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
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.collection.ArrayMap;
import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import eu.faircode.netguard.DatabaseHelper;
import eu.faircode.netguard.ServiceSinkhole;

public class TrackerList {
    private static final String TAG = TrackerList.class.getSimpleName();

    private static final List<String> ignoreDomains = Collections.singletonList("cloudfront.net, fastly.net");

    private static TrackerList instance;
    private static final Map<String, Tracker> hostnameToTracker = new ConcurrentHashMap<>();
    private static boolean domainBasedBlocking;
    public static Set<String> trackingIps = new HashSet<>();

    private final DatabaseHelper databaseHelper;

    private TrackerList(Context c) {
        databaseHelper = DatabaseHelper.getInstance(c);
        loadTrackers(c);
    }

    public void loadTrackers(Context c) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(c);
        domainBasedBlocking = prefs.getBoolean("domain_based_blocked", false);

        loadXrayTrackers(c);
        loadDisconnectTrackers(c); // loaded last to overwrite X-Ray hosts with extra category information
        loadIpBlocklist(c);
    }

    private void loadIpBlocklist(Context c) {
        try {
            InputStream is = c.getAssets().open("ip_blocklist.txt");
            BufferedReader bfr = new BufferedReader(new InputStreamReader(is));
            String line;
            while ( (line = bfr.readLine()) != null) {
                if (line.startsWith("#"))
                    continue;
                trackingIps.add(line);
            }
        } catch (IOException e) {
            Log.e(TAG, "Loading IP blocklist failed.. ", e);
        }
    }

    public static TrackerList getInstance(Context c) {
        if (instance == null)
            instance = new TrackerList(c);

        return instance;
    }

    public static String TRACKER_HOSTLIST = "TRACKER_HOSTLIST";
    private static final Tracker hostlistTracker = new Tracker(TRACKER_HOSTLIST, UNCATEGORISED);

    /**
     * Identifies tracker hosts
     *
     * @param hostname A hostname of interest
     * @return A {@link Tracker} object, if host is null, null otherwise
     */
    public static Tracker findTracker(@NonNull String hostname) {
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

        if (t == null
                && ServiceSinkhole.mapHostsBlocked.containsKey(hostname))
            if (domainBasedBlocking)
                return hostlistTracker;
            else {
                t = new Tracker(hostname, UNCATEGORISED);
                hostnameToTracker.put(hostname, t);
                return t;
            }

        if (t == null
                && trackingIps.contains(hostname))
            if (domainBasedBlocking)
                return hostlistTracker;
            else {
                return new Tracker(hostname, UNCATEGORISED);
            }

        return t;
    }

    /**
     * Retrieves information for all apps
     *
     * @return A cursor pointing to the data. Caller must close the cursor.
     * Cursor should have app name and leak summation based on a sort type
     */
    public synchronized Pair<Pair<Map<Integer, Integer>, Integer>, Pair<Map<Integer, Integer>, Integer>> getTrackerCountsAndTotal() {
        Map<Integer, Set<String>> trackers = new ArrayMap<>();
        Map<Integer, Set<String>> trackersWeek = new ArrayMap<>();

        Cursor cursor = databaseHelper.getHosts();
        long limit = new Date().getTime() - 7 * 24 * 3600 * 1000L;
        if (cursor.moveToFirst()) {
            do {
                int appUid = cursor.getInt(cursor.getColumnIndex("uid"));
                String hostname = cursor.getString(cursor.getColumnIndex("daddr"));
                Tracker tracker = findTracker(hostname);
                checkTracker(trackers, appUid, tracker);

                long time = cursor.getLong(cursor.getColumnIndex("time"));
                if (time > limit)
                    checkTracker(trackersWeek, appUid, tracker);
            } while (cursor.moveToNext());
        }
        cursor.close();

        return new Pair<>(countTrackers(trackers), countTrackers(trackersWeek));
    }

    private void checkTracker(Map<Integer, Set<String>> trackers, int appUid, Tracker tracker) {
        Set<String> observedTrackers = trackers.get(appUid);
        if (observedTrackers == null) {
            observedTrackers = new HashSet<>();
            trackers.put(appUid, observedTrackers);
        }

        if (tracker != null)
            observedTrackers.add(tracker.getName());
    }

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

        Cursor cursor = databaseHelper.getHosts(uid);
        if (cursor.moveToFirst()) {
            outer:
            do {
                String host = cursor.getString(cursor.getColumnIndex("daddr"));
                long lastSeen = cursor.getLong(cursor.getColumnIndex("time"));
                boolean uncertain = cursor.getInt(cursor.getColumnIndex("uncertain")) == 2;

                Tracker tracker = findTracker(host);
                if (tracker == null)
                    continue;

                String category = tracker.category;
                String name = tracker.name;
                if (category == null || category.equals("null"))
                    category = name;

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

                        if (child.lastSeen < lastSeen)
                            child.lastSeen = lastSeen;

                        continue outer;
                    }
                }

                Tracker child = new Tracker(name, category, lastSeen);
                child.addHost(host);
                categoryCompany.getChildren().add(child);
            } while (cursor.moveToNext());
        }
        cursor.close();

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

        try {
            // Read JSON
            InputStream is = c.getAssets().open("xray-blacklist.json");
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            is.close();
            String json = new String(buffer, StandardCharsets.UTF_8);

            // Each JSON array entry contains tracker company with domains
            JSONArray jsonCompanies = new JSONArray(json);
            for (int i = 0; i < jsonCompanies.length(); i++) {
                JSONObject jsonCompany = jsonCompanies.getJSONObject(i);

                Tracker tracker;
                String name = jsonCompany.getString("owner_name");
                boolean necessary = jsonCompany.has("necessary")
                        && jsonCompany.getBoolean("necessary");

                // Necessary tracker are identified at lowest company level
                if (!jsonCompany.isNull("root_parent")
                        && !necessary)
                    name = jsonCompany.getString("root_parent");

                // Check if we've seen a tracker from the same root company
                tracker = rootParents.get(name);
                if (tracker == null) {
                    String category = necessary ? "Content" : UNCATEGORISED;
                    tracker = new Tracker(name, category);
                    tracker.country = jsonCompany.getString("country");
                    rootParents.put(name, tracker);
                }

                // Add domains to tracker map
                JSONArray domains = jsonCompany.getJSONArray("doms");
                for (int j = 0; j < domains.length(); j++) {
                    String dom = domains.getString(j);

                    if (ignoreDomains.contains(dom))
                        continue;

                    addTrackerDomain(tracker, dom);
                }
            }
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Loading X-Ray list failed.. ", e);
        }
    }

    /**
     * Load Disconnect.me tracker list
     *
     * @param c Context
     */
    private void loadDisconnectTrackers(Context c) {
        try {
            /* Read domain list:
             *
             * File is a reversed string, because some anti-virus scanners found the list suspicious
             * More here: https://github.com/TrackerControl/tracker-control-android/issues/30
             */
            InputStream is = c.getAssets().open("disconnect-blacklist.reversed.json");
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            is.close();
            String reversedJson = new String(buffer, StandardCharsets.UTF_8);
            String json = new StringBuilder(reversedJson).reverse().toString();

            // Parse Disconnect.me list
            JSONObject disconnect = new JSONObject(json);
            JSONObject categories = (JSONObject) disconnect.get("categories");
            for (Iterator<String> it = categories.keys(); it.hasNext(); ) {
                String categoryName = it.next();
                JSONArray category = (JSONArray) categories.get(categoryName);
                for (int i = 0; i < category.length(); i++) {
                    // Found tracker, now add to list
                    JSONObject jsonTracker = category.getJSONObject(i);
                    String trackerName = jsonTracker.keys().next();
                    Tracker tracker = new Tracker(trackerName, categoryName);

                    // Parse tracker domains
                    JSONObject trackerHomeUrls = (JSONObject) jsonTracker.get(trackerName);
                    for (Iterator<String> iter = trackerHomeUrls.keys(); iter.hasNext(); ) {
                        String trackerHomeUrl = iter.next();

                        // Skip non-domains fields
                        if (!(trackerHomeUrls.get(trackerHomeUrl) instanceof JSONArray))
                            continue;

                        JSONArray urls = (JSONArray) trackerHomeUrls.get(trackerHomeUrl);
                        for (int j = 0; j < urls.length(); j++) {
                            String dom = urls.getString(j);

                            if (ignoreDomains.contains(dom))
                                continue;

                            addTrackerDomain(tracker, dom);
                        }
                    }
                }
            }
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Loading Disconnect.me list failed.. ", e);
        }
    }

    private void addTrackerDomain(Tracker tracker, String dom) {
        if (domainBasedBlocking) {
            Tracker t = new Tracker(dom + " (" + tracker.getName() + ")", tracker.category);
            t.country = tracker.country;
            hostnameToTracker.put(dom, t);
        } else
            hostnameToTracker.put(dom, tracker);
    }
}
