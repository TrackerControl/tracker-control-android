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

import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import net.kollnig.missioncontrol.R;
import net.kollnig.missioncontrol.data.AppBlocklistController;
import net.kollnig.missioncontrol.data.Tracker;
import net.kollnig.missioncontrol.data.TrackerCategory;

import java.util.ArrayList;
import java.util.List;

import eu.faircode.netguard.Util;

/**
 * {@link RecyclerView.Adapter} that can display a {@link Tracker}.
 */
public class TrackersListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ITEM = 1;

    private final String TAG = TrackersListAdapter.class.getSimpleName();
    private final RecyclerView recyclerView;
    private final Integer mAppUid;
    private List<TrackerCategory> mValues = new ArrayList<>();
    private Context mContext;

    public TrackersListAdapter(Context c,
                               RecyclerView root,
                               Integer appUid) {
        recyclerView = root;
        mContext = c;
        mAppUid = appUid;

        // Removes blinks
        ((SimpleItemAnimator) recyclerView.getItemAnimator()).setSupportsChangeAnimations(false);
    }

    public void set(List<TrackerCategory> items) {
        mValues = items;
        notifyDataSetChanged();
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        if (viewType == TYPE_ITEM) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.list_item_trackers, parent, false);
            return new VHItem(view);
        } else if (viewType == TYPE_HEADER) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.disclaimer, parent, false);
            return new VHHeader(view);
        }

        throw new RuntimeException("there is no type that matches the type " + viewType + " + make sure your using types correctly");
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder _holder, int position) {
        if (_holder instanceof VHItem) {
            VHItem holder = (VHItem) _holder;

            // Load data item
            final TrackerCategory tracker = getItem(position);
            holder.mTracker = tracker;

            // Add data to view
            holder.mTrackerName.setText(tracker.name);
            holder.mTotalTrackers.setText(mContext.getResources().getQuantityString(
                    R.plurals.n_trackers_found, tracker.getChildren().size(), tracker.getChildren().size())
                    + ":");
            holder.mTrackerDetails.setText(
                    TextUtils.join("\n\n", tracker.getChildren()));


            if (Util.isPlayStoreInstall(mContext)) {
                holder.mSwitch.setVisibility(View.GONE);
                return;
            }
            final AppBlocklistController w = AppBlocklistController.getInstance(mContext);
            holder.mSwitch.setChecked(
                    w.blockedTracker(mAppUid, tracker.name)
            );
            holder.mSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (!buttonView.isPressed()) return;

                if (isChecked) {
                    w.block(mAppUid, tracker.name);
                } else {
                    w.unblock(mAppUid, tracker.name);
                }
            });
            holder.mView.setOnClickListener(v -> holder.mSwitch.toggle());

            //cast holder to VHItem and set data
        } else if (_holder instanceof VHHeader) {
            //cast holder to VHHeader and set data for header.
        }
    }

    @Override
    public int getItemCount() {
        return mValues.size() + 1;
    }

    @Override
    public int getItemViewType(int position) {
        if (isPositionHeader(position))
            return TYPE_HEADER;

        return TYPE_ITEM;
    }

    private boolean isPositionHeader(int position) {
        return position == 0;
    }

    private TrackerCategory getItem(int position) {
        return mValues.get(position - 1);
    }

    class VHItem extends RecyclerView.ViewHolder {
        final View mView;
        final TextView mTrackerDetails;
        final TextView mTrackerName;
        final TextView mTotalTrackers;
        final Switch mSwitch;
        TrackerCategory mTracker;

        VHItem(View view) {
            super(view);
            mView = view;
            mTrackerDetails = view.findViewById(R.id.tracker_details);
            mTrackerName = view.findViewById(R.id.root_name);
            mTotalTrackers = view.findViewById(R.id.total_trackers);
            mSwitch = view.findViewById(R.id.switch_tracker);
        }
    }

    class VHHeader extends RecyclerView.ViewHolder {
        VHHeader(View view) {
            super(view);
        }
    }
}