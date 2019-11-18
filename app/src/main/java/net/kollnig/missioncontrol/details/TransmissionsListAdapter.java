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
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.SimpleItemAnimator;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;

import net.kollnig.missioncontrol.BuildConfig;
import net.kollnig.missioncontrol.R;
import net.kollnig.missioncontrol.data.Tracker;
import net.kollnig.missioncontrol.main.AppBlocklistController;

import java.util.List;

/**
 * {@link RecyclerView.Adapter} that can display a {@link Tracker}.
 */
public class TransmissionsListAdapter extends RecyclerView.Adapter<TransmissionsListAdapter.ViewHolder> {

	private final List<Tracker> mValues;
	private final RecyclerView recyclerView;
	private final String mAppId;
	private Context mContext;

	public TransmissionsListAdapter (List<Tracker> items,
	                                 Context c,
	                                 RecyclerView root,
	                                 String appId) {
		mValues = items;
		recyclerView = root;
		mContext = c;
		mAppId = appId;

		// Removes blinks
		((SimpleItemAnimator) recyclerView.getItemAnimator()).setSupportsChangeAnimations(false);
	}

	@Override
	public ViewHolder onCreateViewHolder (ViewGroup parent, int viewType) {
		View view = LayoutInflater.from(parent.getContext())
				.inflate(R.layout.list_item_transmissions, parent, false);
		return new ViewHolder(view);
	}

	@Override
	public void onBindViewHolder (final ViewHolder holder, final int position) {
		// Load data item
		final Tracker tracker = mValues.get(position);
		holder.mTracker = tracker;

		// Add data to view
		holder.mTrackerName.setText(tracker.name);
		holder.mTotalTrackers.setText(mContext.getResources().getQuantityString(
				R.plurals.n_trackers_found, tracker.children.size(), tracker.children.size()));
		holder.mTrackerDetails.setText(
				"• " + TextUtils.join("\n• ", tracker.children));


		if (BuildConfig.FLAVOR.equals("play")) {
			holder.mSwitch.setVisibility(View.GONE);
			return;
		}
		final AppBlocklistController w = AppBlocklistController.getInstance(mContext);
		holder.mSwitch.setChecked(
				w.blockedTracker(mAppId, tracker.name)
		);
		holder.mSwitch.setOnCheckedChangeListener((v, isChecked) -> {
			if (isChecked) {
				if (!w.blockedApp(mAppId)) {
					w.addToBlocklist(mAppId);
					for (Tracker tracker1 : mValues) {
						w.removeFromBlocklist(mAppId, tracker1.name);
					}
				}
				w.addToBlocklist(mAppId, tracker.name);
			} else {
				if (!w.blockedApp(mAppId))
					return;
				w.removeFromBlocklist(mAppId, tracker.name);
			}
		});
		holder.mView.setOnClickListener(v -> holder.mSwitch.toggle());
	}

	@Override
	public int getItemCount () {
		return mValues.size();
	}

	class ViewHolder extends RecyclerView.ViewHolder {
		final View mView;
		final TextView mTrackerDetails;
		final TextView mTrackerName;
		final TextView mTotalTrackers;
		final ImageView mImageArrow;
		final Switch mSwitch;
		Tracker mTracker;

		ViewHolder (View view) {
			super(view);
			mView = view;
			mTrackerDetails = view.findViewById(R.id.tracker_details);
			mTrackerName = view.findViewById(R.id.root_name);
			mTotalTrackers = view.findViewById(R.id.total_trackers);
			mSwitch = view.findViewById(R.id.switch_tracker);
			mImageArrow = view.findViewById(R.id.imArrow);
		}
	}
}
