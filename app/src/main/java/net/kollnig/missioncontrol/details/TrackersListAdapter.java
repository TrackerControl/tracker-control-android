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

import static net.kollnig.missioncontrol.data.TrackerList.TRACKER_HOSTLIST;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.AsyncTask;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import net.kollnig.missioncontrol.Common;
import net.kollnig.missioncontrol.R;
import net.kollnig.missioncontrol.analysis.AnalysisException;
import net.kollnig.missioncontrol.analysis.TrackerLibraryAnalyser;
import net.kollnig.missioncontrol.data.InternetBlocklist;
import net.kollnig.missioncontrol.data.Tracker;
import net.kollnig.missioncontrol.data.TrackerBlocklist;
import net.kollnig.missioncontrol.data.TrackerCategory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import eu.faircode.netguard.Rule;
import eu.faircode.netguard.ServiceSinkhole;
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
    private final SharedPreferences apply;
    private List<TrackerCategory> mValues = new ArrayList<>();

    public TrackersListAdapter(Context c,
                               RecyclerView v,
                               Integer appUid,
                               String appId) {
        mContext = c;
        mAppUid = appUid;
        mAppId = appId;

        apply = mContext.getSharedPreferences("apply", Context.MODE_PRIVATE);

        // Removes blinks
        ((SimpleItemAnimator) Objects.requireNonNull(v.getItemAnimator())).setSupportsChangeAnimations(false);
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

            // Show warning for browser apps
            Intent urlIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.wikipedia.org/"));
            urlIntent.setPackage(mAppId);
            if (Common.isCallable(mContext, urlIntent)
                    && !Util.isPlayStoreInstall())
                view.findViewById(R.id.cardNotSupported).setVisibility(View.VISIBLE);

            // Find trackers in app code
            staticTrackerAnalysis(view);

            return new VHHeader(view);
        }

        throw new RuntimeException("there is no type that matches the type " + viewType + " + make sure your using types correctly");
    }

    /**
     * Do static analysis of tracker libraries in app code, and display results
     *
     * @param view The tracker view to render the results of the analysis in
     */
    private void staticTrackerAnalysis(View view) {
        TextView tvDetectedTrackers = view.findViewById(R.id.tvDetectedTrackers);
        ProgressBar pbTrackerDetection = view.findViewById(R.id.pbDetectedTrackers);
        pbTrackerDetection.setVisibility(View.VISIBLE);

        if (mContext instanceof Activity) {
            Activity activity = (Activity) mContext;

            AsyncTask.execute(() -> {
                String trackerString = null;
                AnalysisException ex = null;

                try {
                    TrackerLibraryAnalyser analyser = new TrackerLibraryAnalyser(mContext);
                    trackerString = analyser.analyse(mAppId);
                } catch (AnalysisException e) {
                    ex = e;
                } finally {
                    final String res;

                    if (ex != null)
                        res = ex.getMessage();
                    else if (trackerString != null)
                        res = String.format(activity.getString(R.string.detected_trackers), trackerString);
                    else
                        res = mContext.getString(R.string.failed_loading_cached_analysis);

                    activity.runOnUiThread(() -> {
                        tvDetectedTrackers.setText(res);
                        tvDetectedTrackers.setVisibility(View.VISIBLE);
                        pbTrackerDetection.setVisibility(View.GONE);
                    });
                }
            });
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder _holder, int position) {
        final InternetBlocklist w = InternetBlocklist.getInstance(mContext);

        if (_holder instanceof VHItem) {
            VHItem holder = (VHItem) _holder;

            // Hide blocking tips on Play Store
            holder.mBlockingTip.setVisibility(Util.isPlayStoreInstall() ? View.GONE : View.VISIBLE);

            // Load data
            final TrackerBlocklist b = TrackerBlocklist.getInstance(mContext);
            final TrackerCategory trackerCategory = getItem(position);
            final String trackerCategoryName = trackerCategory.getCategoryName();

            // Display uncertainty
            holder.mUncertain.setVisibility(trackerCategory.isUncertain() ? View.VISIBLE : View.GONE);

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
                            if (t != null)
                                updateText(tv, t);
                            return tv;
                        }

                        private void updateText(TextView tv, Tracker t) {
                            String name = t.getName();
                            if (name.equals(TRACKER_HOSTLIST))
                                name = getContext().getString(R.string.tracker_hostlist);

                            String title = name;
                            if (t.lastSeen != 0)
                                title += " (" + Util.relativeTime(t.lastSeen) + ")";

                            List<String> sortedHosts = new ArrayList<>(t.getHosts());
                            Collections.sort(sortedHosts);
                            String hosts = TextUtils.join("\n• ", sortedHosts);

                            boolean categoryBlocked = b.blocked(mAppUid, trackerCategoryName);
                            Spannable spannable;
                            if (!categoryBlocked || Util.isPlayStoreInstall()) {
                                String text = String.format("%s\n• %s", title, hosts);
                                spannable = new SpannableString(text);
                            } else {
                                boolean companyBlocked = b.blocked(mAppUid,
                                        TrackerBlocklist.getBlockingKey(t));
                                String status = getContext().getString(companyBlocked ? R.string.blocked : R.string.allowed);
                                int color = ContextCompat.getColor(getContext(), companyBlocked ? R.color.colorPrimary : R.color.colorAccent);

                                String text = String.format("%s %s\n• %s", title, status, hosts);

                                spannable = new SpannableString(text);

                                spannable.setSpan(new ForegroundColorSpan(color),
                                        title.length() + 1,
                                        (title + status).length() + 1,
                                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                            }

                            spannable.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                                    0,
                                    name.length(),
                                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

                            tv.setText(spannable, TextView.BufferType.SPANNABLE);
                        }
                    };
            holder.mCompaniesList.setAdapter(trackersAdapter);

            if (Util.isPlayStoreInstall(mContext)) {
                holder.mSwitchTracker.setVisibility(View.GONE);
            } else {
                boolean enabled = apply.getBoolean(mAppId, true) && !w.blockedInternet(mAppUid);
                holder.mSwitchTracker.setEnabled(enabled);
                holder.mSwitchTracker.setChecked(
                        b.blocked(mAppUid, trackerCategoryName)
                );
                holder.mSwitchTracker.setOnCheckedChangeListener((buttonView, hasBecomeChecked) -> {
                    if (!buttonView.isPressed()) return; // to fix errors

                    if (hasBecomeChecked)
                        b.block(mAppUid, trackerCategoryName);
                    else {
                        b.unblock(mAppUid, trackerCategoryName);
                        Toast.makeText(mContext, R.string.category_unblocked, Toast.LENGTH_SHORT).show();
                    }

                    trackersAdapter.notifyDataSetChanged();
                });
                if (enabled)
                    holder.mCompaniesList.setOnItemClickListener((adapterView, v, i, l) -> {
                        if (w.blockedInternet(mAppUid))
                            return;

                        Tracker t = trackersAdapter.getItem(i);
                        if (t == null) return;

                        final boolean blockedTrackerCategory = b.blocked(mAppUid, t.category);
                        if (!blockedTrackerCategory) {
                            Toast.makeText(mContext, R.string.category_unblocked_warning, Toast.LENGTH_SHORT).show();
                            return;
                        }

                        boolean blockedTracker = b.blockedTracker(mAppUid, t);
                        if (blockedTracker)
                            b.unblock(mAppUid, t);
                        else
                            b.block(mAppUid, t);

                        trackersAdapter.notifyDataSetChanged();
                    });
                else
                    holder.mCompaniesList.setOnItemClickListener(null);
            }

            //cast holder to VHItem and set data
        } else if (_holder instanceof VHHeader) {
            VHHeader holder = (VHHeader) _holder;

            // Explain blocking, except in Play Store version
            if (Util.isPlayStoreInstall())
                holder.mLibraryExplanation.setText(R.string.trackers_static_explanation_playstore);
            else
                holder.mLibraryExplanation.setText(R.string.trackers_static_explanation);

            // Exclusion from VPN
            holder.mSwitchVPN.setChecked(apply.getBoolean(mAppId, true));
            holder.mSwitchVPN.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (!buttonView.isPressed()) return; // to fix errors
                apply.edit().putBoolean(mAppId, isChecked).apply();

                // Move expensive operations off the main thread to prevent UI freezing
                // Rule.clearCache() can block waiting for a lock held by Rule.getRules()
                AsyncTask.execute(() -> {
                    Rule.clearCache(mContext);
                    ServiceSinkhole.reload("app blocking changed", mContext, false);
                });

                notifyDataSetChanged();
            });

            // Blocking of Internet
            holder.mSwitchInternet.setEnabled(apply.getBoolean(mAppId, true));
            holder.mSwitchInternet.setChecked(
                    !w.blockedInternet(mAppUid)
            );
            holder.mSwitchInternet.setOnCheckedChangeListener((buttonView, hasBecomeChecked) -> {
                if (!buttonView.isPressed()) return; // to fix errors

                if (hasBecomeChecked)
                    w.unblock(mAppUid);
                else
                    w.block(mAppUid);

                notifyDataSetChanged();
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
        final TextView mBlockingTip;
        final TextView mUncertain;

        VHItem(View view) {
            super(view);
            mTrackerCategoryName = view.findViewById(R.id.root_name);
            mCompaniesList = view.findViewById(R.id.details_list);
            mSwitchTracker = view.findViewById(R.id.switch_tracker);
            mBlockingTip = view.findViewById(R.id.tvBlockingTip);
            mUncertain = view.findViewById(R.id.tvUncertain);
        }
    }

    static class VHHeader extends RecyclerView.ViewHolder {
        final TextView mLibraryExplanation;
        final Switch mSwitchInternet;
        final Switch mSwitchVPN;

        VHHeader(View view) {
            super(view);
            mLibraryExplanation = view.findViewById(R.id.tvLibraryExplanation);
            mSwitchInternet = view.findViewById(R.id.switch_internet);
            mSwitchVPN = view.findViewById(R.id.switch_vpn);
        }
    }
}
