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

package net.kollnig.missioncontrol.details;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import net.kollnig.missioncontrol.data.Database;
import net.kollnig.missioncontrol.data.Tracker;

import java.util.List;

import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import eu.faircode.netguard.R;

/**
 * A fragment representing a list of Items.
 */
public class TrackersFragment extends Fragment {
	private static final String ARG_APP_ID = "app-id";
	Database database;
	private String mAppId;

	/**
	 * Mandatory empty constructor for the fragment manager to instantiate the
	 * fragment (e.g. upon screen orientation changes).
	 */
	public TrackersFragment () {
	}

	public static TrackersFragment newInstance (String appId) {
		TrackersFragment fragment = new TrackersFragment();
		Bundle args = new Bundle();
		args.putString(ARG_APP_ID, appId);
		fragment.setArguments(args);
		return fragment;
	}

	@Override
	public void onCreate (Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Bundle bundle = getArguments();
		mAppId = bundle.getString(ARG_APP_ID);
	}

	@Override
	public View onCreateView (LayoutInflater inflater, ViewGroup container,
	                          Bundle savedInstanceState) {
		View v = inflater.inflate(R.layout.fragment_trackers, container, false);

		// Set the adapter
		Context context = v.getContext();

		// Load data
		database = Database.getInstance(context);
		List<Tracker> details = database.getTrackers(mAppId);


		// Load in RecyclerView
		RecyclerView recyclerView = v.findViewById(R.id.transmissions_list);
		TextView emptyView = v.findViewById(R.id.no_items);
		Button btnLaunch = v.findViewById(R.id.btnLaunch);
		if (details.size() == 0) {
			btnLaunch.setVisibility(View.VISIBLE);
			emptyView.setVisibility(View.VISIBLE);
			recyclerView.setVisibility(View.GONE);

			Context c = getContext();
			if (c != null) {
				Intent intent = c.getPackageManager().getLaunchIntentForPackage(mAppId);
				final Intent launch = (intent == null ||
						intent.resolveActivity(c.getPackageManager()) == null ? null : intent);
				btnLaunch.setVisibility(launch == null ? View.GONE : View.VISIBLE);
				btnLaunch.setOnClickListener(view -> c.startActivity(launch));
			} // TODO: Possibly, never hide button
		} else {
			btnLaunch.setVisibility(View.GONE);
			emptyView.setVisibility(View.GONE);
			recyclerView.setVisibility(View.VISIBLE);

			recyclerView.setLayoutManager(new LinearLayoutManager(context));
			recyclerView.setAdapter(
					new TrackersListAdapter(details, getContext(), recyclerView, mAppId));
		}

		return v;
	}
}
