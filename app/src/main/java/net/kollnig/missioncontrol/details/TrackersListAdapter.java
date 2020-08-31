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
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import net.kollnig.missioncontrol.R;
import net.kollnig.missioncontrol.data.InternetBlocklist;
import net.kollnig.missioncontrol.data.Tracker;
import net.kollnig.missioncontrol.data.TrackerBlocklist;
import net.kollnig.missioncontrol.data.TrackerCategory;

import java.util.ArrayList;
import java.util.List;

import eu.faircode.netguard.Util;

/**
 * {@link RecyclerView.Adapter} that can display a {@link TrackerCategory}.
 */
public class TrackersListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ITEM = 1;

    private final String TAG = TrackersListAdapter.class.getSimpleName();
    private final Integer mAppUid;
    private final String mAppId;
    private final Context mContext;
    private List<TrackerCategory> mValues = new ArrayList<>();
    private final SharedPreferences apply;

    public TrackersListAdapter(Context c,
                               RecyclerView v,
                               Integer appUid,
                               String appId) {
        mContext = c;
        mAppUid = appUid;
        mAppId = appId;

        apply = mContext.getSharedPreferences("apply", Context.MODE_PRIVATE);

        // Removes blinks
        ((SimpleItemAnimator) v.getItemAnimator()).setSupportsChangeAnimations(false);
    }

    public static String renderDetails(Tracker t, boolean blocked) {
        List<String> sortedHosts = new ArrayList<>(t.getHosts());
        java.util.Collections.sort(sortedHosts);
        String hosts = "\n• " + TextUtils.join("\n• ", sortedHosts);

        String title;
        if (t.lastSeen != 0) {
            title = t.name + " (" + Util.relativeTime(t.lastSeen) + ")";
        } else {
            title = t.name;
        }

        return blocked ? title + hosts : title + " (Unblocked)" + hosts;
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

            // Load data
            final TrackerBlocklist b = TrackerBlocklist.getInstance(mContext);
            final TrackerCategory trackerCategory = getItem(position);
            final String trackerCategoryName = trackerCategory.getCategoryName();

            // Add data to view
            holder.mTrackerCategoryName.setText(trackerCategory.getDisplayName(mContext));
            final ArrayAdapter<Tracker> trackersAdapter =
                    new ArrayAdapter<Tracker>(mContext, R.layout.list_item_trackers_details, trackerCategory.getChildren()) {
                        @Override
                        public @NonNull
                        View getView(int pos, @Nullable View convertView,
                                     @NonNull ViewGroup parent) {
                            TextView tv = (TextView) super.getView(pos, convertView, parent);

                            Tracker t = getItem(pos);
                            if (t != null) {
                                boolean blocked = b.blocked(mAppUid,
                                        TrackerBlocklist.getBlockingKey(t));
                                tv.setText(renderDetails(t, blocked));
                            }

                            return tv;
                        }

                    };
            holder.mCompaniesList.setAdapter(trackersAdapter);

            if (Util.isPlayStoreInstall(mContext)) {
                holder.mSwitchTracker.setVisibility(View.GONE);
            } else {
                holder.mSwitchTracker.setEnabled(apply.getBoolean(mAppId, true));
                holder.mSwitchTracker.setChecked(
                        b.blocked(mAppUid, trackerCategoryName)
                );
                holder.mSwitchTracker.setOnCheckedChangeListener((buttonView, hasBecomeChecked) -> {
                    if (!buttonView.isPressed()) return; // to fix errors

                    if (hasBecomeChecked) {
                        b.block(mAppUid, trackerCategoryName);
                    } else {
                        b.unblock(mAppUid, trackerCategoryName);
                    }
                });
                holder.mCompaniesList.setOnItemClickListener((adapterView, v, i, l) -> {
                    Tracker t = trackersAdapter.getItem(i);
                    if (t == null) return;

                    final boolean blockedTrackerCategory = b.blocked(mAppUid, t.category);
                    if (!blockedTrackerCategory) {
                        Toast.makeText(mContext, "Need to block category", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    boolean blockedTracker = b.blockedTracker(mAppUid, t);
                    if (blockedTracker)
                        b.unblock(mAppUid, t);
                    else
                        b.block(mAppUid, t);

                    trackersAdapter.notifyDataSetChanged();
                });
            }

            //cast holder to VHItem and set data
        } else if (_holder instanceof VHHeader) {
            VHHeader holder = (VHHeader) _holder;

            // Exclusion from VPN
            holder.mSwitchVPN.setChecked(apply.getBoolean(mAppId, true));
            holder.mSwitchVPN.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (!buttonView.isPressed()) return; // to fix errors
                apply.edit().putBoolean(mAppId, isChecked).apply();
                notifyDataSetChanged();
            });

            // Blocking of Internet
            final InternetBlocklist w = InternetBlocklist.getInstance(mContext);
            holder.mSwitchInternet.setEnabled(apply.getBoolean(mAppId, true));
            holder.mSwitchInternet.setChecked(
                    w.blockedInternet(mAppUid)
            );
            holder.mSwitchInternet.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (!buttonView.isPressed()) return; // to fix errors

                if (isChecked) {
                    w.block(mAppUid);
                } else {
                    w.unblock(mAppUid);
                }
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
        final Switch mSwitchTracker;

        VHItem(View view) {
            super(view);
            mTrackerCategoryName = view.findViewById(R.id.root_name);
            mCompaniesList = view.findViewById(R.id.details_list);
            mSwitchTracker = view.findViewById(R.id.switch_tracker);
        }
    }

    static class VHHeader extends RecyclerView.ViewHolder {
        final Switch mSwitchInternet;
        final Switch mSwitchVPN;

        VHHeader(View view) {
            super(view);
            mSwitchInternet = view.findViewById(R.id.switch_internet);
            mSwitchVPN = view.findViewById(R.id.switch_vpn);
        }
    }
}