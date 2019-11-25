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

package net.kollnig.missioncontrol.data;

import android.content.ContentValues;
import android.content.Context;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.AsyncTask;
import android.text.TextUtils;
import android.util.Log;

import com.google.common.net.InternetDomainName;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import androidx.collection.ArrayMap;

public class Database {
	/* ****** COLUMN NAMES PERTAINING TO {@link #TABLE_HISTORY} ****** */
	public static final String COLUMN_APPID = "appname";
	public static final String COLUMN_REMOTE_IP = "remoteIp";
	public static final String COLUMN_HOSTNAME = "hostname";
	public static final String COLUMN_COMPANYNAME = "companyName";
	public static final String COLUMN_COMPANYOWNER = "companyOwner";
	public static final String COLUMN_COUNT = "count";
	private static final String TAG = Database.class.getSimpleName();
	private static final String DATABASE_NAME = "trackers.db";
	/**
	 * Keeps history of leaks
	 */
	private static final String TABLE_HISTORY = "TABLE_HISTORY";
	/* ****** COLUMN NAMES PERTAINING TO ALL TABLES ****** */
	private static final String COLUMN_ID = "_id";
	/**
	 * Used in {@link #TABLE_HISTORY} to indicate when the leak occured
	 */
	private static final String COLUMN_TIME = "timestampt";
	private static final int DATABASE_VERSION = 3;
	public static Map<String, Company> hostnameToCompany = new ArrayMap<>();
	public static Set<String> necessaryCompanies = new HashSet<>();
	private static Database instance;
	private final SQLHandler sqlHandler;
	private Set<OnDatabaseClearListener> clearListeners = new HashSet<>();
	private SQLiteDatabase _database;
	private AssetManager assetManager;

	/**
	 * Database constructor
	 */
	private Database (Context c) {
		sqlHandler = new SQLHandler(c);
		assetManager = c.getAssets();
		loadTrackerDomains(c);
	}

	/**
	 * Singleton getter.
	 *
	 * @param c context used to open the database
	 * @return The current instance of PrivacyDB, if none, a new instance is created.
	 * After calling this method, the database is open for writing.
	 */
	public static Database getInstance (Context c) {
		if (instance == null)
			instance = new Database(c);

		if (instance._database == null) {
			instance._database = instance.sqlHandler.getWritableDatabase();
		}

		return instance;
	}

	public static Company getCompany (String hostname) {
		Company company = null;

		if (hostnameToCompany.containsKey(hostname)) {
			company = hostnameToCompany.get(hostname);
		} else {
			// one level below public suffix
			try {
				InternetDomainName parsedDomain = InternetDomainName.from(hostname);
				String topDomain = parsedDomain.topPrivateDomain().toString();
				if (hostnameToCompany.containsKey(topDomain)) {
					company = hostnameToCompany.get(topDomain);
				} else {
					String topDomainUnderRegistrySuffix = parsedDomain.topDomainUnderRegistrySuffix().toString();
					if (hostnameToCompany.containsKey(topDomainUnderRegistrySuffix)) {
						company = hostnameToCompany.get(topDomainUnderRegistrySuffix);
					}
				}
			} catch (Exception e) {

			}
		}

		return company;
	}

	public void addListener (OnDatabaseClearListener l) {
		clearListeners.add(l);
	}

	public void removeListener (OnDatabaseClearListener l) {
		clearListeners.remove(l);
	}

	/**
	 * Retrieves information for all apps
	 *
	 * @return A cursor pointing to the data. Caller must close the cursor.
	 * Cursor should have app name and leak summation based on a sort type
	 */
	public synchronized Map<String, Integer> getApps () {
		Map<String, Integer> trackerCounts = new ArrayMap<>();

		String[] columns = new String[]{COLUMN_APPID,
				"COUNT( DISTINCT " + COLUMN_COMPANYNAME + " ) AS " +
						COLUMN_COUNT};

		Cursor cursor = getDatabase().query(TABLE_HISTORY,
				columns,
				COLUMN_COMPANYNAME + " IS NOT NULL", null,
				COLUMN_APPID, // groupBy
				null,
				COLUMN_COUNT + " DESC");

		if (cursor.moveToFirst()) {
			do {
				String appId = cursor.getString(cursor.getColumnIndex(COLUMN_APPID));
				int trackerCount = cursor.getInt(cursor.getColumnIndex(COLUMN_COUNT));

				trackerCounts.put(appId, trackerCount);
			} while (cursor.moveToNext());
		}

		cursor.close();

		return trackerCounts;
	}

	private SQLiteDatabase getDatabase () {
		if (this.isClose()) {
			_database = sqlHandler.getWritableDatabase();
		}
		return _database;
	}

	public synchronized String[] printTable (String table) {
		Cursor c = getDatabase().rawQuery("SELECT * FROM " + table, null);
		if (c.getCount() <= 0) {
			c.close();
			Log.d(TAG, "null");
			return null;
		}

		String[] names = new String[c.getCount()];
		Log.d(TAG, Arrays.toString(c.getColumnNames()));

		int i = 0;
		for (c.moveToFirst(); !c.isAfterLast(); c.moveToNext()) {

			String toPrint = "";
			for (int j = 0; j < c.getColumnCount(); j++)
				toPrint += c.getString(j) + ", ";
			Log.d(TAG, toPrint);
			names[i] = c.getString(0);
			i++;
		}
		c.close();
		return names;
	}

	public long count () {
		long count = DatabaseUtils.queryNumEntries(getDatabase(), TABLE_HISTORY);

		return count;
	}

	public String[] printLeaks () {
		Cursor c = getDatabase().query(TABLE_HISTORY, new String[]{
						COLUMN_ID, COLUMN_APPID, COLUMN_HOSTNAME},
				null, null, null, null,
				COLUMN_TIME + " DESC");
		if (c.getCount() <= 0) {
			c.close();
			Log.d(TAG, "null");
			return null;
		}

		String[] leaks = new String[c.getCount()];
		Log.d(TAG, Arrays.toString(c.getColumnNames()));

		int i = 0;
		for (c.moveToFirst(); !c.isAfterLast(); c.moveToNext()) {

			String toPrint = "";
			for (int j = 0; j < c.getColumnCount(); j++)
				toPrint += c.getString(j) + ", ";
			//Log.d(TAG, toPrint);

			leaks[i] = toPrint;
			i++;
		}
		c.close();
		return leaks;
	}

	public Cursor getAppInfo (String appId) {
		return getDatabase().rawQuery(
				"SELECT * FROM " + TABLE_HISTORY, null);
	}

	public String getCompanies (String appId) {
		Cursor c = getDatabase().query(TABLE_HISTORY, new String[]{
						COLUMN_COMPANYNAME, "COUNT ( * )"},
				COLUMN_APPID + " = ? AND " + COLUMN_COMPANYNAME + " IS NOT NULL",
				new String[]{appId}, COLUMN_COMPANYNAME, null,
				null);
		if (c.getCount() <= 0) {
			c.close();
			return null;
		}

		String[] leaks = new String[c.getCount()];

		int i = 0;
		for (c.moveToFirst(); !c.isAfterLast(); c.moveToNext()) {
			leaks[i] = c.getString(0) + ": " + c.getString(1);
			i++;
		}
		c.close();

		return TextUtils.join("\n", leaks);
	}

	/**
	 * Retrieves information for all apps and how many leaks there were based on sort type
	 *
	 * @return A cursor pointing to the data. Caller must close the cursor.
	 * Cursor should have app name and leak summation based on a sort type
	 */
	public synchronized Cursor getPrivacyLeaksReport () {
		String[] columns = new String[]{COLUMN_ID, COLUMN_APPID,
				"COUNT( DISTINCT " + COLUMN_COMPANYNAME + " ) AS " +
						COLUMN_COUNT};

		return getDatabase().query(TABLE_HISTORY,
				columns,
				COLUMN_COMPANYNAME + " IS NOT NULL", null,
				COLUMN_APPID, // groupBy
				null,
				COLUMN_COUNT + " DESC");
	}


	public synchronized List<Tracker> getTrackers () {
		return getTrackers(null);
	}

	/**
	 * Retrieves information about all seen trackers
	 *
	 * @return A list of seen trackers
	 */
	public synchronized List<Tracker> getTrackers (String mAppId) {
		Map<String, Tracker> ownerToCompany = new ArrayMap<>();

		String[] columns = new String[]{COLUMN_COMPANYOWNER, COLUMN_COMPANYNAME,
				"COUNT( * ) AS " + COLUMN_COUNT};

		Cursor cursor;
		if (mAppId == null) {
			cursor = getDatabase().query(TABLE_HISTORY,
					columns,
					COLUMN_COMPANYNAME + " IS NOT NULL", null,
					COLUMN_COMPANYOWNER + "," + COLUMN_COMPANYNAME, // groupBy
					null,
					null);
		} else {
			cursor = getDatabase().query(TABLE_HISTORY,
					columns,
					COLUMN_COMPANYNAME + " IS NOT NULL AND " + COLUMN_APPID + " = ?",
					new String[]{mAppId},
					COLUMN_COMPANYOWNER + "," + COLUMN_COMPANYNAME, // groupBy
					null,
					COLUMN_COMPANYNAME + " ASC");
		}

		if (cursor.moveToFirst()) {
			do {
				String owner = cursor.getString(cursor.getColumnIndex(COLUMN_COMPANYOWNER));
				String name = cursor.getString(cursor.getColumnIndex(COLUMN_COMPANYNAME));
				int packetCount = cursor.getInt(cursor.getColumnIndex(COLUMN_COUNT));
				if (owner == null || owner.equals("null")) owner = name;

				Tracker ownerCompany = ownerToCompany.get(owner);
				if (ownerCompany == null) {
					ownerCompany = new Tracker();
					ownerCompany.name = owner;
					ownerCompany.packetCount = packetCount;
					ownerCompany.children = new ArrayList<>();
					ownerToCompany.put(owner, ownerCompany);
				} else {
					ownerCompany.packetCount += packetCount;
				}

				Tracker child = new Tracker();
				child.name = name;
				child.owner = owner;
				child.packetCount = packetCount;
				ownerCompany.children.add(child);
			} while (cursor.moveToNext());
		}

		List<Tracker> trackerList = new ArrayList<>(ownerToCompany.values());

		return trackerList;
	}

	/**
	 * Retrieves information for one app
	 *
	 * @return A list with tracker details
	 */
	public synchronized List<Tracker> getAppDetails (String appId) {
		Map<String, Tracker> trackerMap = new HashMap<>();

		String[] columns = new String[]{COLUMN_COMPANYNAME, COLUMN_COMPANYOWNER, COLUMN_REMOTE_IP, COLUMN_HOSTNAME,
				"COUNT( * ) AS " +
						COLUMN_COUNT};

		Cursor cursor = getDatabase().query(TABLE_HISTORY,
				columns,
				COLUMN_APPID + " = ?", new String[]{appId},
				COLUMN_COMPANYNAME + "," + COLUMN_COMPANYOWNER + "," + COLUMN_REMOTE_IP + "," + COLUMN_HOSTNAME,
				null,
				null);

		// Read data
		if (cursor.moveToFirst()) {
			do {
				String name = cursor.getString(cursor.getColumnIndex(COLUMN_COMPANYNAME));
				String owner = cursor.getString(cursor.getColumnIndex(COLUMN_COMPANYOWNER));
				String hostname = cursor.getString(cursor.getColumnIndex(COLUMN_HOSTNAME));
				String ip = cursor.getString(cursor.getColumnIndex(COLUMN_REMOTE_IP));
				int count = cursor.getInt(cursor.getColumnIndex(COLUMN_COUNT));

				// First, prepare tracker entry
				Tracker tracker = trackerMap.get(name);
				if (tracker == null) {
					tracker = new Tracker();
					tracker.packetCount = count;
					tracker.name = name;
					tracker.owner = owner;
					tracker.hosts = new ArrayList<>();
					trackerMap.put(name, tracker);
				} else {
					tracker.packetCount += count;
				}

				// Now, add details about host
				Host host = new Host();
				host.hostname = hostname;
				host.packetCount = count;

				// Add everything to list
				tracker.hosts.add(host);
			} while (cursor.moveToNext());
		}

		// Sort items descending
		List<Tracker> trackerList = new ArrayList<>(trackerMap.values());
		Collections.sort(trackerList);
		for (Tracker tracker : trackerList) {
			Collections.sort(tracker.hosts);
		}

		cursor.close();

		return trackerList;
	}

	/**
	 * Clears {@link #TABLE_HISTORY}.
	 */
	public synchronized void clearHistory () {
		String query = "DELETE FROM " + TABLE_HISTORY;
		getDatabase().execSQL(query);

		for (OnDatabaseClearListener l : clearListeners) {
			try {
				l.onDatabaseClear();
			} catch (Exception e) {
				Log.d(TAG, "Notification of change failed.");
			}
		}
	}

	/**
	 * Deletes all entries in the database
	 */
	public synchronized void clearDatabase () {
		getDatabase().delete(TABLE_HISTORY, null, null);
	}

	/**
	 * Close the database
	 */
	public synchronized void close () {
		sqlHandler.close();
		_database = null;
	}

	private synchronized boolean isClose () {
		return _database == null;
	}

	/**
	 * Logs the leak for historical purposes
	 *
	 * @param appName  the name of the app responsible for the leak
	 * @param remoteIp the IP address contacted
	 * @param hostname the resolved hostname from remoteIp
	 * @return the row ID of the updated row, or -1 if an error occurred
	 */
	private synchronized long logPacket (String appName, String remoteIp, String hostname, String companyName, String companyOwner) {
		// Add leak to history
		ContentValues cv = new ContentValues();
		cv.put(COLUMN_APPID, appName);
		cv.put(COLUMN_HOSTNAME, hostname);
		cv.put(COLUMN_COMPANYNAME, companyName);
		cv.put(COLUMN_COMPANYOWNER, companyOwner);
		cv.put(COLUMN_TIME, System.currentTimeMillis());
		cv.put(COLUMN_REMOTE_IP, remoteIp);

		return getDatabase().insert(TABLE_HISTORY, null, cv);
	}

	public void logPacketAsyncTask (Context context,
	                                String packageName, String remoteIp, String hostname) {
		LogPacketTask task = new LogPacketTask(context, packageName, remoteIp, hostname);
		task.execute();
	}


	public void loadTrackerDomains (Context context) {
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
				Boolean necessary;
				if (jsonCompany.has("necessary")) {
					necessary = jsonCompany.getBoolean("necessary");
					necessaryCompanies.add(name);
				} else {
					necessary = false;
				}
				company = new Company(country, name, parent, necessary);

				JSONArray domains = jsonCompany.getJSONArray("doms");
				for (int j = 0; j < domains.length(); j++) {
					hostnameToCompany.put(domains.getString(j), company);
				}
			}
		} catch (IOException | JSONException e) {
			Log.d(TAG, "Loading companies failed.. ", e);
		}
	}

	public interface OnDatabaseClearListener {
		void onDatabaseClear ();
	}

	private static class SQLHandler extends SQLiteOpenHelper {

		SQLHandler (Context context) {
			super(context, DATABASE_NAME, null, DATABASE_VERSION);
		}

		/**
		 * Called when database is first created
		 */
		@Override
		public void onCreate (SQLiteDatabase db) {
			db.execSQL("CREATE TABLE " + TABLE_HISTORY + "("
					+ COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
					+ COLUMN_APPID + " TEXT NOT NULL, "
					+ COLUMN_REMOTE_IP + " TEXT NOT NULL, "
					+ COLUMN_HOSTNAME + " TEXT NOT NULL, "
					+ COLUMN_COMPANYNAME + " TEXT, "
					+ COLUMN_COMPANYOWNER + " TEXT, "
					+ COLUMN_TIME + " INTEGER DEFAULT 0);");
		}

		/**
		 * If database exists, this method will be called
		 */
		@Override
		public void onUpgrade (SQLiteDatabase db, int oldVersion, int newVersion) {
			db.execSQL("DROP TABLE IF EXISTS " + TABLE_HISTORY);
			onCreate(db);
		}

	}

	static class LogPacketTask extends AsyncTask<Void, Void, Boolean> {
		private final Context mContext;
		private final String packageName;
		private final String remoteIp;
		private final String hostname;

		LogPacketTask (Context context, String packageName, String remoteIp, String hostname) {
			this.mContext = context;
			this.packageName = packageName;
			this.remoteIp = remoteIp;
			this.hostname = hostname;
		}

		@Override
		protected Boolean doInBackground (Void... voids) {
			Database db = Database.getInstance(mContext);

			Company company = getCompany(hostname);
			if (company == null) {
				db.logPacket(this.packageName, this.remoteIp, this.hostname, null, null);
			} else {
				db.logPacket(this.packageName, this.remoteIp, this.hostname, company.name, company.owner);
			}

			return true;
		}
	}
}
