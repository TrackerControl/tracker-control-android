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
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class AppBlocklistController {
	public static final String SHARED_PREFS_BLOCKLIST_APPS_KEY = "APPS_BLOCKLIST_APPS_KEY";
	private static AppBlocklistController instance;

	final public static String PREF_BLOCKLIST = "blocklist";

	/**
	 * Whilst blockmap is a list of apps to block, the set is a set of trackers not to block.
	 */
	Map<String, Set<String>> blockmap = new ConcurrentHashMap<>();

	AppBlocklistController (Context c) {
		// Private because of singleton
		Context mContext = c;

		// Initialize Concurrent Set using values from shared preferences if possible.
		if (mContext != null) {
			SharedPreferences prefs = c.getSharedPreferences(PREF_BLOCKLIST, Context.MODE_PRIVATE);
			Set<String> set = prefs.getStringSet(SHARED_PREFS_BLOCKLIST_APPS_KEY, null);
			if (set != null) {
				blockmap.clear();
				for (String id : set) {
					Set<String> subset = prefs.getStringSet
							(SHARED_PREFS_BLOCKLIST_APPS_KEY + "_" + id, null);
					if (subset == null) {
						subset = new HashSet<>();
					}
					blockmap.put(id, subset);
				}
			}
		}
	}

	public Set<String> getBlocklist () {
		return blockmap.keySet();
	}

	public Set<String> getSubset (String id) {
		return blockmap.get(id);
	}

	public boolean containsTracker (String appId, String tracker) {
		Set<String> app = blockmap.get(appId);
		if (app == null)
			return true;

		return app.contains(tracker);
	}

	public void clear () {
		blockmap.clear();
	}

	public synchronized void block (String id, String tracker) {
		Set<String> app = blockmap.get(id);
		if (app == null)
			return;
		app.remove(tracker);
	}

	public synchronized void unblock (String id, String tracker) {
		Set<String> app = blockmap.get(id);

		if (app == null) {
			app = new HashSet<>();
			blockmap.put(id, app);
		}

		app.add(tracker);
	}

	/**
	 * Singleton getter.
	 *
	 * @param c context used to access shared preferences from.
	 * @return The current instance of the AppBlocklistController, if none, a new instance is created.
	 */
	public static AppBlocklistController getInstance (Context c) {
		if (instance == null) {
			// Create the instance
			instance = new AppBlocklistController(c);
		}
		// Return the instance
		return instance;
	}

	public boolean blockedTracker (String appId, String tracker) {
		Set<String> trackers = this.getSubset(appId);
		if (trackers == null) {
			return true;
		}

		return !trackers.contains(tracker); // negate since it's a whitelist
	}
}
