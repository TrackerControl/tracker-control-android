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
 * along with TrackerControl. If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright © 2019–2020 Konrad Kollnig (University of Oxford)
 */

package net.kollnig.missioncontrol.details;

import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import net.kollnig.missioncontrol.R;
import net.kollnig.missioncontrol.data.InternetBlocklist;
import net.kollnig.missioncontrol.data.Tracker;
import net.kollnig.missioncontrol.data.TrackerBlocklist;
import net.kollnig.missioncontrol.data.TrackerCategory;

import java.util.ArrayList;
import java.util.List;

import eu.faircode.netguard.ServiceSinkhole;
import eu.faircode.netguard.Util;

/**
 * {@link RecyclerView.Adapter} that can display a {@link TrackerCategory}.
 */
public class TrackersListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ITEM = 1;

    private final String TAG = TrackersListAdapter.class.getSimpleName();
    private final RecyclerView recyclerView;
    private final Integer mAppUid;
    private final String mAppId;
    private final Context mContext;
    private List<TrackerCategory> mValues = new ArrayList<>();
    private Intent launch;

    public TrackersListAdapter(Context c,
                               RecyclerView root,
                               Integer appUid,
                               String appId) {
        recyclerView = root;
        mContext = c;
        mAppUid = appUid;
        mAppId = appId;

        Intent intent = mContext.getPackageManager().getLaunchIntentForPackage(mAppId);
        launch = (intent == null ||
                intent.resolveActivity(mContext.getPackageManager()) == null ? null : intent);

        // Removes blinks
        ((SimpleItemAnimator) recyclerView.getItemAnimator()).setSupportsChangeAnimations(false);
    }

    public void set(List<TrackerCategory> items) {
        mValues = items;
        notifyDataSetChanged();
    }

    @Override
    @NonNull
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_ITEM) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.list_item_trackers, parent, false);
            return new VHItem(view);
        } else if (viewType == TYPE_HEADER) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.list_item_trackers_header, parent, false);
            return new VHHeader(view);
        }

        throw new RuntimeException("there is no type that matches the type " + viewType + " + make sure your using types correctly");
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder _holder, int position) {
        if (_holder instanceof VHItem) {
            VHItem holder = (VHItem) _holder;

            // Load data item
            final TrackerCategory trackerCategory = getItem(position);
            holder.mTrackerCategory = trackerCategory;

            // Add data to view
            holder.mTrackerCategoryName.setText(trackerCategory.name);
            final ArrayAdapter<Tracker> trackersAdapter =
                    new ArrayAdapter<>(mContext, R.layout.list_item_trackers_details, trackerCategory.getChildren());
            holder.mCompaniesList.setAdapter(trackersAdapter);
            holder.mCompaniesList.setOnItemLongClickListener((adapterView, view, i, l) -> {
                Tracker t = trackersAdapter.getItem(i);
                //Toast.makeText(mContext, t.toString(), Toast.LENGTH_SHORT).show();
                return true;
            });

            if (Util.isPlayStoreInstall(mContext)) {
                holder.mSwitch.setVisibility(View.GONE);
            } else {
                final TrackerBlocklist w = TrackerBlocklist.getInstance(mContext);
                holder.mSwitch.setChecked(
                        w.blockedTracker(mAppUid, trackerCategory.name)
                );
                holder.mSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    if (!buttonView.isPressed()) return; // to fix errors

                    if (isChecked) {
                        w.block(mAppUid, trackerCategory.name);
                    } else {
                        w.unblock(mAppUid, trackerCategory.name);
                    }

                    ServiceSinkhole.reload("trackers changed", mContext, false);
                });
            }

            //cast holder to VHItem and set data
        } else if (_holder instanceof VHHeader) {
            VHHeader holder = (VHHeader) _holder;

            if (launch == null) {
                holder.mLaunch.setVisibility(View.GONE);
            } else {
                holder.mLaunch.setOnClickListener(view -> mContext.startActivity(launch));
            }

            final InternetBlocklist w = InternetBlocklist.getInstance(mContext);
            holder.mSwitch.setChecked(
                    w.blockedInternet(mAppUid)
            );

            holder.mSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (!buttonView.isPressed()) return; // to fix errors

                if (isChecked) {
                    w.block(mAppUid);
                } else {
                    w.unblock(mAppUid);
                }

                ServiceSinkhole.reload("internet access changed", mContext, false);
            });
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

    static class VHItem extends RecyclerView.ViewHolder {
        final TextView mTrackerCategoryName;
        final ListView mCompaniesList;
        final Switch mSwitch;
        TrackerCategory mTrackerCategory;

        VHItem(View view) {
            super(view);
            mTrackerCategoryName = view.findViewById(R.id.root_name);
            mCompaniesList = view.findViewById(R.id.details_list);
            mSwitch = view.findViewById(R.id.switch_tracker);
        }
    }

    static class VHHeader extends RecyclerView.ViewHolder {
        final Switch mSwitch;
        final Button mLaunch;

        VHHeader(View view) {
            super(view);
            mSwitch = view.findViewById(R.id.switch_internet);
            mLaunch = view.findViewById(R.id.btnLaunch);
        }
    }
}