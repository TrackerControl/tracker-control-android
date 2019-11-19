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
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;

import net.kollnig.missioncontrol.R;
import net.kollnig.missioncontrol.data.App;
import net.kollnig.missioncontrol.data.Database;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static net.kollnig.missioncontrol.main.BlocklistController.PREF_BLOCKLIST;

/**
 * A simple {@link Fragment} subclass.
 * Use the {@link AppsFragment#newInstance} factory method to
 * create an instance of this fragment.
 */
public class AppsFragment extends Fragment implements SwipeRefreshLayout.OnRefreshListener,
		Database.OnDatabaseClearListener {

	private static final String TAG = AppsFragment.class.getSimpleName();
	AppsListAdapter mAppsListAdapter;
	RecyclerView mRecyclerView;
	Database database;
	SwipeRefreshLayout mSwipeRefreshLayout;
	ProgressBar pbApps;

	public AppsFragment () {
		// Required empty public constructor
	}

	public static AppsFragment newInstance () {
		AppsFragment fragment = new AppsFragment();
		return fragment;
	}

	public static void savePrefs (Context c) {
		// Save currently Selected Apps to Shared Prefs
		AppBlocklistController controller = AppBlocklistController.getInstance(c);
		Set<String> appSet = controller.getBlocklist();
		String prefKey = controller.getPrefKey();
		SharedPreferences prefs = c.getSharedPreferences(PREF_BLOCKLIST, Context.MODE_PRIVATE);
		SharedPreferences.Editor editor = prefs.edit();
		editor.clear();
		editor.putStringSet(prefKey, appSet);
		for (String id : appSet) {
			Set<String> subset = controller.getSubset(id);
			editor.putStringSet(controller.getPrefSubsetKey(id), subset);
		}
		editor.apply();
	}

	SharedPreferences settingsPref;

	@Override
	public void onCreate (Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setHasOptionsMenu(true);

		settingsPref =
				android.support.v7.preference.PreferenceManager
						.getDefaultSharedPreferences(getContext());
	}

	@Override
	public void onCreateOptionsMenu (Menu menu, MenuInflater inflater) {
		super.onCreateOptionsMenu(menu, inflater);
		//inflater.inflate(R.menu.menu_main, menu);
	}

	@Override
	public boolean onOptionsItemSelected (MenuItem item) {
		switch (item.getItemId()) {
			case R.id.action_reset_monitoring:
				AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
				builder.setMessage(R.string.confirm_reset)
						.setTitle(R.string.are_you_sure);
				builder.setPositiveButton(R.string.ok, (dialog, id) -> {
					Database database = Database.getInstance(getActivity());
					database.clearLeaksHistory();
					dialog.dismiss();
				});
				builder.setNegativeButton(android.R.string.cancel, (dialog, i) -> dialog.dismiss());
				AlertDialog dialog = builder.create();
				dialog.show();
				return true;
			default:
				return super.onOptionsItemSelected(item);
		}
	}

	@Override
	public View onCreateView (LayoutInflater inflater, ViewGroup container,
	                          Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_apps, container, false);
	}

	@Override
	public void onViewCreated (View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		mAppsListAdapter = new AppsListAdapter(this);
		mRecyclerView = view.findViewById(R.id.list_main);
		mRecyclerView.setHasFixedSize(true);
		mRecyclerView.scrollToPosition(0);
		mRecyclerView.setAdapter(mAppsListAdapter);

		mSwipeRefreshLayout = view.findViewById(R.id.main_swipe_refresh);
		mSwipeRefreshLayout.setOnRefreshListener(this);
		pbApps = view.findViewById(R.id.pbMainList);

		database = Database.getInstance(getContext());
		database.addListener(this);

		boolean mShowSystemApps = settingsPref.getBoolean
				(SettingsActivity.KEY_PREF_SYSTEMAPPS_SWITCH, false);
		(new AppsRefreshTask(this, mShowSystemApps)).execute();
	}

	@Override
	public void onPause () {
		super.onPause();
		savePrefs(getContext());
	}

	@Override
	public void onResume () {
		super.onResume();
		boolean mShowSystemApps = settingsPref.getBoolean
				(SettingsActivity.KEY_PREF_SYSTEMAPPS_SWITCH, false);
		(new AppsRefreshTask(this, mShowSystemApps)).execute();
	}

	@Override
	public void onDetach () {
		super.onDetach();
		database.removeListener(this);
	}

	public void updateUI (List<App> installedApps) {
		mAppsListAdapter.setAppsList(installedApps);
		mAppsListAdapter.notifyDataSetChanged();
		mRecyclerView.scrollToPosition(0);

		mSwipeRefreshLayout.setRefreshing(false);
		mSwipeRefreshLayout.setVisibility(View.VISIBLE);
		pbApps.setVisibility(View.GONE);
	}

	@Override
	public void onRefresh () {
		boolean mShowSystemApps = settingsPref.getBoolean
				(SettingsActivity.KEY_PREF_SYSTEMAPPS_SWITCH, false);
		(new AppsRefreshTask(this, mShowSystemApps)).execute();
	}

	public void onDatabaseClear () {
		boolean mShowSystemApps = settingsPref.getBoolean
				(SettingsActivity.KEY_PREF_SYSTEMAPPS_SWITCH, false);
		(new AppsRefreshTask(this, mShowSystemApps)).execute();
	}

	static class AppsRefreshTask extends AsyncTask<Void, Void, Boolean> {
		WeakReference<AppsFragment> weakFragment;
		Database database;
		List<App> installedApps;
		AppBlocklistController appBlocklistController;
		Boolean mShowSystemApps;

		AppsRefreshTask (AppsFragment f, Boolean showSystemApps) {
			database = Database.getInstance(f.getContext());
			weakFragment = new WeakReference<>(f);
			appBlocklistController = AppBlocklistController.getInstance(f.getContext());
			mShowSystemApps = showSystemApps;
		}

		@Override
		protected Boolean doInBackground (Void... voids) {

			installedApps = appBlocklistController.load(mShowSystemApps);
			Map<String, Integer> trackerCounts = database.getApps();

			for (App app : installedApps) {
				Integer trackerCount = trackerCounts.get(app.id);
				if (trackerCount != null) {
					app.trackerCount = trackerCount;
				} else {
					app.trackerCount = 0;
				}
			}

			Collections.sort(installedApps);

			return true;
		}

		@Override
		protected void onPostExecute (Boolean success) {
			AppsFragment f = weakFragment.get();
			if (f != null) {
				f.updateUI(installedApps);
			}
		}
	}
}
