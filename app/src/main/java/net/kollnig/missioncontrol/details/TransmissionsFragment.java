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

package net.kollnig.missioncontrol.details;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import net.kollnig.missioncontrol.R;
import net.kollnig.missioncontrol.data.Database;
import net.kollnig.missioncontrol.data.Tracker;

import java.util.List;

import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * A fragment representing a list of Items.
 */
public class TransmissionsFragment extends Fragment {
	private static final String ARG_APP_ID = "app-id";
	Database database;
	private String mAppId;

	/**
	 * Mandatory empty constructor for the fragment manager to instantiate the
	 * fragment (e.g. upon screen orientation changes).
	 */
	public TransmissionsFragment () {
	}

	public static TransmissionsFragment newInstance (String appId) {
		TransmissionsFragment fragment = new TransmissionsFragment();
		Bundle args = new Bundle();
		args.putString(ARG_APP_ID, appId);
		fragment.setArguments(args);
		return fragment;
	}

	@Override
	public void onCreate (Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Bundle bundle = getArguments();
		mAppId = getArguments().getString(ARG_APP_ID);
	}

	@Override
	public View onCreateView (LayoutInflater inflater, ViewGroup container,
	                          Bundle savedInstanceState) {
		View v = inflater.inflate(R.layout.fragment_transmissions, container, false);

		// Set the adapter
		Context context = v.getContext();

		// Load data
		database = Database.getInstance(context);
		List<Tracker> details = database.getTrackers(mAppId);

		// Load in RecyclerView
		RecyclerView recyclerView = v.findViewById(R.id.transmissions_list);
		TextView emptyView = v.findViewById(R.id.no_items);
		if (details.size() == 0) {
			emptyView.setVisibility(View.VISIBLE);
			recyclerView.setVisibility(View.GONE);
		} else {
			emptyView.setVisibility(View.GONE);
			recyclerView.setVisibility(View.VISIBLE);

			recyclerView.setLayoutManager(new LinearLayoutManager(context));
			recyclerView.setAdapter(
					new TransmissionsListAdapter(details, getContext(), recyclerView, mAppId));
		}

		return v;
	}
}
