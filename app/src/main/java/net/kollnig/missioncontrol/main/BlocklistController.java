/*
 * Copyright (C) 2019 Konrad Kollnig, University of Oxford
 *
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * TrackerControl is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with TrackerControl. If not, see <http://www.gnu.org/licenses/>.
 */

package net.kollnig.missioncontrol.main;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public abstract class BlocklistController {
	final static String PREF_BLOCKLIST = "blocklist";

	/**
	 * Whilst blockmap is a list of apps to block, the set is a set of trackers not to block.
	 */
	Map<String, Set<String>> blockmap = new ConcurrentHashMap<>();

	// Private constructor to prevent instantiation.
	BlocklistController (Context c) {
		// Private because of singleton
		Context mContext = c;

		// Initialize Concurrent Set using values from shared preferences if possible.
		if (mContext != null) {
			SharedPreferences prefs = c.getSharedPreferences(PREF_BLOCKLIST, Context.MODE_PRIVATE);
			Set<String> set = prefs.getStringSet(getPrefKey(), null);
			if (set != null) {
				blockmap.clear();
				for (String id : set) {
					Set<String> subset = prefs.getStringSet(getPrefSubsetKey(id), null);
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

	public abstract String getPrefKey ();

	public String getPrefSubsetKey (String id) {
		return getPrefKey() + "_" + id;
	}

	public boolean blockedApp (String id) {
		return blockmap.containsKey(id);
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

	public synchronized Set<String> block (String id) {
		Set<String> app = blockmap.get(id);
		if (app != null)
			return app;

		app = new HashSet<>();
		blockmap.put(id, app);
		return app;
	}

	public synchronized void block (String id, String tracker) {
		Set<String> app = blockmap.get(id);
		app.remove(tracker);
	}

	public synchronized void unblock (String id, String tracker) {
		Set<String> app = blockmap.get(id);
		app.add(tracker);
	}

	public synchronized void unblock (String id) {
		blockmap.remove(id);
	}

	public int getBlockedCount () {
		return blockmap.size();
	}
}
