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

import android.content.Context;
import android.database.Cursor;
import android.util.Log;

import androidx.collection.ArrayMap;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import eu.faircode.netguard.DatabaseHelper;

public class TrackerList {
    private static final String TAG = TrackerList.class.getSimpleName();
    static Set<String> necessaryTrackers = new HashSet<>();
    private static Map<String, Tracker> hostnameToTracker = new ArrayMap<>();
    private static TrackerList instance;
    private DatabaseHelper databaseHelper;

    /**
     * Database constructor
     */
    private TrackerList(Context c) {
        databaseHelper = DatabaseHelper.getInstance(c);
        loadXrayTrackerDomains(c);
        loadTrackerDomains(c);
    }

    /**
     * Singleton getter.
     *
     * @param c context used to open the database
     * @return The current instance of PrivacyDB, if none, a new instance is created.
     * After calling this method, the database is open for writing.
     */
    public static TrackerList getInstance(Context c) {
        if (instance == null)
            instance = new TrackerList(c);

        return instance;
    }

    /**
     * Retrieves information for all apps
     *
     * @return A cursor pointing to the data. Caller must close the cursor.
     * Cursor should have app name and leak summation based on a sort type
     */
    public synchronized Pair<Map<Integer, Integer>, Integer> getTrackerCountsAndTotal() {
        Map<Integer, Set<String>> trackers = new ArrayMap<>();

        Cursor cursor = databaseHelper.getHosts();

        if (cursor.moveToFirst()) {
            do {
                int uid = cursor.getInt(cursor.getColumnIndex("uid"));
                Set<String> observedTrackers = trackers.get(uid);
                if (observedTrackers == null) {
                    observedTrackers = new HashSet<>();
                    trackers.put(uid, observedTrackers);
                }

                // Add tracker
                String hostname = cursor.getString(cursor.getColumnIndex("daddr"));
                Tracker tracker = findTracker(hostname);
                if (tracker != null)
                    observedTrackers.add(tracker.getName());
            } while (cursor.moveToNext());
        }
        cursor.close();

        // Reduce to counts
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
    public synchronized List<TrackerCategory> getAppTrackers(int uid) {
        Map<String, TrackerCategory> categoryToTracker = new ArrayMap<>();

        Cursor cursor = databaseHelper.getHosts(uid);

        if (cursor.moveToFirst()) {
            outer:
            do {
                String host = cursor.getString(cursor.getColumnIndex("daddr"));
                long lastSeen = cursor.getLong(cursor.getColumnIndex("time"));

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

                // check if tracker has already been added
                for (Tracker child : categoryCompany.getChildren()) {
                    if (child.name != null
                            && child.name.equals(name)){
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
        List<TrackerCategory> trackerList = new ArrayList<>(categoryToTracker.values());

        // sort lists
        Collections.sort(trackerList, (o1, o2) -> o1.name.compareTo(o2.name));
        for (TrackerCategory child : trackerList) {
            Collections.sort(child.getChildren(), (o1, o2) -> o2.lastSeen.compareTo(o1.lastSeen));
        }

        return trackerList;
    }

    public static Tracker findTracker(String hostname) {
        Tracker tracker = null;

        if (hostnameToTracker.containsKey(hostname)) {
            tracker = hostnameToTracker.get(hostname);
        } else { // check subdomains
            for (int i = 0; i < hostname.length(); i++) {
                if (hostname.charAt(i) == '.') {
                    tracker = hostnameToTracker.get(hostname.substring(i + 1));
                    if (tracker != null)
                        break;
                }
            }
        }

        return tracker;
    }

    private void loadXrayTrackerDomains(Context context) {
        Map<String, Tracker> companies = new HashMap<>();

        try {
            // Read domain list
            InputStream is = context.getAssets().open("companyDomains.json");
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            is.close();
            String json = new String(buffer, StandardCharsets.UTF_8);

            JSONArray jsonCompanies = new JSONArray(json);
            for (int i = 0; i < jsonCompanies.length(); i++) {
                JSONObject jsonCompany = jsonCompanies.getJSONObject(i);

                Tracker tracker;
                String country = jsonCompany.getString("country");
                String name = jsonCompany.getString("owner_name");
                boolean necessary;
                if (jsonCompany.has("necessary")) {
                    necessary = jsonCompany.getBoolean("necessary");
                    necessaryTrackers.add(name);
                } else {
                    necessary = false;
                }
                if (!jsonCompany.isNull("root_parent")
                        && !necessary) { // necessary tracker are identified at lowest level
                    name = jsonCompany.getString("root_parent");
                }

                tracker = companies.get(name);
                if (tracker == null) {
                    tracker = new Tracker(name, "Uncategorised", necessary);
                    companies.put(name, tracker);
                }

                JSONArray domains = jsonCompany.getJSONArray("doms");
                for (int j = 0; j < domains.length(); j++) {
                    hostnameToTracker.put(domains.getString(j), tracker);
                }
            }
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Loading xray list failed.. ", e);
        }
    }

    private void loadTrackerDomains(Context context) {
        try {
            // Read domain list
            // File is a reversed string, because some anti-virus scanners found the list suspicious
            // More here: https://github.com/OxfordHCC/tracker-control-android/issues/30
            InputStream is = context.getAssets().open("disconnect-blacklist.reversed.json");
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            is.close();
            String reversedJson = new String(buffer, StandardCharsets.UTF_8);
            String json = new StringBuilder(reversedJson).reverse().toString();

            JSONObject disconnect = new JSONObject(json);
            JSONObject categories = (JSONObject) disconnect.get("categories");
            for (Iterator<String> it = categories.keys(); it.hasNext(); ) {
                String categoryName = it.next();
                JSONArray category = (JSONArray) categories.get(categoryName);
                for (int i = 0; i < category.length(); i++) {
                    JSONObject jsonTracker = category.getJSONObject(i);
                    String trackerName = jsonTracker.keys().next();

                    Tracker tracker = new Tracker(trackerName, categoryName, false);

                    JSONObject trackerHomeUrls = (JSONObject) jsonTracker.get(trackerName);
                    for (Iterator<String> iter = trackerHomeUrls.keys(); iter.hasNext(); ) {
                        String trackerHomeUrl = iter.next();
                        if (!(trackerHomeUrls.get(trackerHomeUrl) instanceof JSONArray))
                            continue; // some have further, non-array fields

                        JSONArray urls = (JSONArray) trackerHomeUrls.get(trackerHomeUrl);

                        for (int j = 0; j < urls.length(); j++) {
                            hostnameToTracker.put(urls.getString(j), tracker);
                        }
                    }
                }
            }
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Loading disconnect list failed.. ", e);
        }
    }
}
