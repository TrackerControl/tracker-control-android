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
 * along with TrackerControl.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2019 Konrad Kollnig, University of Oxford
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
	static Set<String> necessaryCompanies = new HashSet<>();
	private static Map<String, Company> hostnameToCompany = new ArrayMap<>();
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
	public static TrackerList getInstance (Context c) {
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
	public synchronized Map<Integer, Integer> getTrackerCounts() {
		Map<Integer, Set<Company>> trackers = new ArrayMap<>();

		Cursor cursor = databaseHelper.getHosts();

		if (cursor.moveToFirst()) {
			do {
				int uid = cursor.getInt(cursor.getColumnIndex("uid"));
				Set<Company> companies = trackers.get(uid);
				if (companies == null) {
					companies = new HashSet<>();
					trackers.put(uid, companies);
				}

				// Add company
				String hostname = cursor.getString(cursor.getColumnIndex("daddr"));
				Company company = getCompany(hostname);
				if (company != null)
					companies.add(company);
			} while (cursor.moveToNext());
		}
		cursor.close();

		// Reduce to counts
		Map<Integer, Integer> trackerCounts = new ArrayMap<>();
		for (Map.Entry<Integer, Set<Company>> entry : trackers.entrySet()) {
			trackerCounts.put(entry.getKey(), entry.getValue().size());
		}

		return trackerCounts;
	}

	/**
	 * Retrieve info for CSV export
	 * @param appId The id of the app to be dumped
	 * @return All found trackers
	 */
	public Cursor getAppInfo (String appId) {
		//return getDatabase().rawQuery(
		//		"SELECT * FROM " + TABLE_HISTORY + " WHERE " + COLUMN_APPID + " = ?", new String[]{appId});
		return null;
	}

	/**
	 * Retrieves information about all seen trackers
	 *
	 * @return A list of seen trackers
	 */
	public synchronized List<Tracker> getAppTrackers(int uid) {
		Map<String, Tracker> ownerToCompany = new ArrayMap<>();

		Cursor cursor = databaseHelper.getHosts(uid);

		if (cursor.moveToFirst()) {
			outer: do {
				String hostname = cursor.getString(cursor.getColumnIndex("daddr"));
				Company company = getCompany(hostname);
				if (company == null)
					continue;

				String owner = company.owner;
				String name = company.name;
				if (owner == null || owner.equals("null")) owner = name;

				Tracker ownerCompany = ownerToCompany.get(owner);
				if (ownerCompany == null) {
					ownerCompany = new Tracker();
					ownerCompany.name = owner;
					ownerCompany.children = new ArrayList<>();
					ownerToCompany.put(owner, ownerCompany);
				}

				// avoid children duplicates
				for (Tracker child: ownerCompany.children) {
					if (child.name.equals(name))
						continue outer;
				}

				Tracker child = new Tracker();
				child.name = name;
				child.owner = owner;
				ownerCompany.children.add(child);
			} while (cursor.moveToNext());
		}

		cursor.close();

		// map to list
		List<Tracker> trackerList = new ArrayList<>(ownerToCompany.values());

		// sort lists
		Collections.sort(trackerList, (o1, o2) -> o1.name.compareTo(o2.name));
		for (Tracker child: trackerList) {
			Collections.sort(child.children, (o1, o2) -> o1.name.compareTo(o2.name));
		}

		return trackerList;
	}

	public Company getCompany (String hostname) {
		Company company = null;

		if (hostnameToCompany.containsKey(hostname)) {
			company = hostnameToCompany.get(hostname);
		} else { // check subdomains
			for (int i = 0; i < hostname.length(); i++){
				if (hostname.charAt(i) == '.') {
					company = hostnameToCompany.get(hostname.substring(i+1));
					if (company != null)
						break;
				}
			}
		}

		return company;
	}

	private void loadXrayTrackerDomains (Context context) {
		Map<String, Company> companies = new HashMap<>();

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

				Company company;
				String country = jsonCompany.getString("country");
				String name = jsonCompany.getString("owner_name");
				String parent = null;
				if (!jsonCompany.isNull("root_parent")) {
					parent = jsonCompany.getString("root_parent");
				}
				boolean necessary;
				if (jsonCompany.has("necessary")) {
					necessary = jsonCompany.getBoolean("necessary");
					necessaryCompanies.add(name);
				} else {
					necessary = false;
				}

				company = companies.get(parent);
				if (company == null) {
					company = new Company(country, parent, "Uncategorised", necessary);
					companies.put(parent, company);
				}

				JSONArray domains = jsonCompany.getJSONArray("doms");
				for (int j = 0; j < domains.length(); j++) {
					hostnameToCompany.put(domains.getString(j), company);
				}
			}
		} catch (IOException | JSONException e) {
			Log.d(TAG, "Loading companies failed.. ", e);
		}
	}

	private void loadTrackerDomains (Context context) {
		try {
			// Read domain list
			InputStream is = context.getAssets().open("disconnect-blacklist.json");
			int size = is.available();
			byte[] buffer = new byte[size];
			is.read(buffer);
			is.close();
			String json = new String(buffer, StandardCharsets.UTF_8);

			JSONObject disconnect = new JSONObject(json);
			JSONObject categories = (JSONObject) disconnect.get("categories");
			for (Iterator<String> it = categories.keys(); it.hasNext(); ) {
				String categoryName = it.next();
				JSONArray category = (JSONArray) categories.get(categoryName);
				for (int i = 0; i < category.length(); i++) {
					JSONObject tracker = category.getJSONObject(i);
					String trackerName = tracker.keys().next();

					Company company = new Company(trackerName, categoryName);

					JSONObject trackerHomeUrls = (JSONObject) tracker.get(trackerName);
					for (Iterator<String> iter = trackerHomeUrls.keys(); iter.hasNext(); ) {
						String trackerHomeUrl = iter.next();
						if (!(trackerHomeUrls.get(trackerHomeUrl) instanceof  JSONArray))
							continue; // some have further, non-array fields

						JSONArray urls = (JSONArray) trackerHomeUrls.get(trackerHomeUrl);

						for (int j = 0; j < urls.length(); j++) {
							hostnameToCompany.put(urls.getString(j), company);
						}
					}
				}
			}
		} catch (IOException | JSONException e) {
			Log.d(TAG, "Loading companies failed.. ", e);
		}
	}
}
