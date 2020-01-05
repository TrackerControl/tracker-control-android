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
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Switch;
import android.widget.TextView;

import net.kollnig.missioncontrol.data.AppBlocklistController;
import net.kollnig.missioncontrol.data.Tracker;

import java.util.List;

import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;
import eu.faircode.netguard.R;
import eu.faircode.netguard.Util;

/**
 * {@link RecyclerView.Adapter} that can display a {@link Tracker}.
 */
public class TrackersListAdapter extends RecyclerView.Adapter<TrackersListAdapter.ViewHolder> {
	private final String TAG = TrackersListAdapter.class.getSimpleName();
	private final List<Tracker> mValues;
	private final RecyclerView recyclerView;
	private final String mAppId;
	private Context mContext;

	public TrackersListAdapter (List<Tracker> items,
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
				.inflate(R.layout.list_item_trackers, parent, false);
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
				R.plurals.n_trackers_found, tracker.children.size(), tracker.children.size())
				+ ":");
		holder.mTrackerDetails.setText(
				"• " + TextUtils.join("\n• ", tracker.children));


		if (Util.isPlayStoreInstall(mContext)) {
			holder.mSwitch.setVisibility(View.GONE);
			return;
		}
		final AppBlocklistController w = AppBlocklistController.getInstance(mContext);
		holder.mSwitch.setChecked(
				w.blockedTracker(mAppId, tracker.name)
		);
		holder.mSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
			if (!buttonView.isPressed()) return;

			if (isChecked) {
				w.block(mAppId, tracker.name);
			} else {
				w.unblock(mAppId, tracker.name);
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
		final Switch mSwitch;
		Tracker mTracker;

		ViewHolder (View view) {
			super(view);
			mView = view;
			mTrackerDetails = view.findViewById(R.id.tracker_details);
			mTrackerName = view.findViewById(R.id.root_name);
			mTotalTrackers = view.findViewById(R.id.total_trackers);
			mSwitch = view.findViewById(R.id.switch_tracker);
		}
	}
}
