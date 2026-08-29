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

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import com.google.android.material.materialswitch.MaterialSwitch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;
import androidx.work.WorkInfo;

import net.kollnig.missioncontrol.Common;
import net.kollnig.missioncontrol.DetailsActivity;
import net.kollnig.missioncontrol.R;
import net.kollnig.missioncontrol.analysis.TrackerAnalysisManager;
import net.kollnig.missioncontrol.data.AppProtectionState;
import net.kollnig.missioncontrol.data.BlockingMode;
import net.kollnig.missioncontrol.data.RemoteRoutingLogic;
import net.kollnig.missioncontrol.data.InternetBlocklist;
import net.kollnig.missioncontrol.data.PausedApps;
import net.kollnig.missioncontrol.data.Tracker;
import net.kollnig.missioncontrol.data.TrackerBlocklist;
import net.kollnig.missioncontrol.data.TrackerCategory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import eu.faircode.netguard.Rule;
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
    private final SharedPreferences tracker_protect;
    private List<TrackerCategory> mValues = new ArrayList<>();

    // Latest state of the library analysis, reflected in the summary row.
    // The full report lives in LibrariesActivity.
    @Nullable
    private WorkInfo mAnalysisWork;

    @Nullable
    private RecyclerView mRecyclerView;

    public TrackersListAdapter(Context c,
            RecyclerView v,
            Integer appUid,
            String appId) {
        mContext = c;
        mAppUid = appUid;
        mAppId = appId;

        apply = mContext.getSharedPreferences("apply", Context.MODE_PRIVATE);
        tracker_protect = mContext.getSharedPreferences("tracker_protect", Context.MODE_PRIVATE);

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

            return new VHHeader(view);
        }

        throw new RuntimeException(
                "there is no type that matches the type " + viewType + " + make sure your using types correctly");
    }

    /**
     * Called by the Fragment when the analysis work state changes, so the
     * summary row can show progress without the caller knowing how it is drawn.
     */
    public void updateAnalysisState(@Nullable WorkInfo workInfo) {
        mAnalysisWork = workInfo;

        // The observer can deliver mid-layout, and RecyclerView rejects a
        // change notification while it is computing one.
        if (mRecyclerView != null && mRecyclerView.isComputingLayout())
            mRecyclerView.post(() -> notifyItemChanged(0));
        else
            notifyItemChanged(0);
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        mRecyclerView = recyclerView;
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        mRecyclerView = null;
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder _holder, int position) {
        final InternetBlocklist w = InternetBlocklist.getInstance(mContext);

        if (_holder instanceof VHItem) {
            VHItem holder = (VHItem) _holder;

            boolean trackerProtectionEnabled = BlockingMode.isTrackerProtectionEnabled(
                    mContext, tracker_protect, mAppId);
            boolean allowGranularControl = !BlockingMode.isMinimalMode(mContext);
            holder.mBlockingTip.setVisibility(allowGranularControl ? View.VISIBLE : View.GONE);

            // Load data
            final TrackerBlocklist b = TrackerBlocklist.getInstance(mContext);
            final TrackerCategory trackerCategory = getItem(position);
            final String trackerCategoryName = trackerCategory.getCategoryName();

            // Display uncertainty
            holder.mUncertain.setVisibility(trackerCategory.isUncertain() ? View.VISIBLE : View.GONE);

            // Add data to view
            String categoryDisplayName = trackerCategory.getDisplayName(mContext);
            holder.mTrackerCategoryName.setText(categoryDisplayName);
            holder.mSwitchTracker.setContentDescription(
                    String.format(mContext.getString(R.string.toggle_block_category_description),
                            categoryDisplayName));
            final ArrayAdapter<Tracker> trackersAdapter = new ArrayAdapter<Tracker>(mContext,
                    R.layout.list_item_trackers_details, trackerCategory.getChildren()) {
                @Override
                public @NonNull View getView(int pos, @Nullable View convertView,
                        @NonNull ViewGroup parent) {
                    TextView tv = (TextView) super.getView(pos, convertView, parent);
                    Tracker t = getItem(pos);
                    if (t != null)
                        updateText(tv, t);
                    return tv;
                }

                @Override
                public boolean areAllItemsEnabled() {
                    return false;
                }

                @Override
                public boolean isEnabled(int pos) {
                    Tracker t = getItem(pos);
                    return t == null || !isAmbiguousDeadToggle(t);
                }

                /**
                 * Ambiguous shared-IP trackers are always allowed at runtime outside
                 * Strict mode (see BlockingModeLogic#blocksAmbiguousTrackerIp), no
                 * matter their configured blocked state. Tapping such a row would
                 * silently toggle hidden state with no runtime effect, so it must be
                 * treated as non-interactive rather than shown as a dead toggle.
                 */
                private boolean isAmbiguousDeadToggle(Tracker t) {
                    return trackerProtectionEnabled
                            && !BlockingMode.isMinimalMode(getContext())
                            && !BlockingMode.isStrictMode(getContext())
                            && t.isAllowedInStandardMode();
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

                    boolean uncertainAllowed = isAmbiguousDeadToggle(t);

                    Spannable spannable;
                    boolean showStatus;
                    boolean companyBlocked;

                    if (!trackerProtectionEnabled) {
                        showStatus = true;
                        companyBlocked = false;
                    } else if (BlockingMode.isMinimalMode(getContext())) {
                        showStatus = true;
                        companyBlocked = TrackerBlocklist.blockedTrackerMinimal(t);
                    } else {
                        boolean categoryBlocked = b.blocked(mAppUid, trackerCategoryName);
                        showStatus = true;
                        companyBlocked = categoryBlocked && b.blocked(mAppUid,
                                TrackerBlocklist.getBlockingKey(t));

                        // In standard mode, ambiguous trackers are allowed at runtime
                        // even if configured as blocked — reflect that in the UI
                        if (companyBlocked
                                && !BlockingMode.isStrictMode(getContext())
                                && t.isAllowedInStandardMode()) {
                            companyBlocked = false;
                        }
                    }

                    if (!showStatus) {
                        String text = String.format("%s\n• %s", title, hosts);
                        spannable = new SpannableString(text);
                    } else {
                        String status = getContext().getString(uncertainAllowed
                                ? R.string.allowed_shared_ip
                                : (companyBlocked ? R.string.blocked : R.string.allowed));
                        int color = ContextCompat.getColor(getContext(),
                                companyBlocked ? R.color.colorPrimary : R.color.colorAccent);

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
                    // Grey out ambiguous shared-IP rows: they are non-interactive
                    // (see isEnabled above), so the toggle isn't a dead click.
                    tv.setEnabled(!uncertainAllowed);
                }
            };
            holder.mCompaniesList.setAdapter(trackersAdapter);

            if (BlockingMode.isMinimalMode(mContext)) {
                // Minimal mode: show read-only blocking status (no granular control)
                holder.mSwitchTracker.setVisibility(View.VISIBLE);
                holder.mSwitchTracker.setEnabled(false);
                holder.mSwitchTracker.setChecked(trackerProtectionEnabled &&
                        !TrackerBlocklist.NECESSARY_CATEGORY.equals(trackerCategoryName));
                holder.mSwitchTracker.setOnCheckedChangeListener(null);
                holder.mCompaniesList.setOnItemClickListener(null);
            } else {
                boolean enabled = currentState(w) == AppProtectionState.PROTECTED;
                holder.mSwitchTracker.setEnabled(enabled);
                holder.mSwitchTracker.setChecked(
                        b.blocked(mAppUid, trackerCategoryName));
                holder.mSwitchTracker.setOnCheckedChangeListener((buttonView, hasBecomeChecked) -> {
                    if (!buttonView.isPressed())
                        return; // to fix errors

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
                        if (t == null)
                            return;

                        // Ambiguous shared-IP trackers are always allowed at runtime
                        // outside Strict mode. The row is marked non-interactive via
                        // the adapter's isEnabled(), but guard here too in case a
                        // click still reaches us, instead of silently doing nothing.
                        if (!BlockingMode.isStrictMode(mContext) && t.isAllowedInStandardMode()) {
                            Toast.makeText(mContext, R.string.allowed_shared_ip, Toast.LENGTH_SHORT).show();
                            return;
                        }

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

            // cast holder to VHItem and set data
        } else if (_holder instanceof VHHeader) {
            VHHeader holder = (VHHeader) _holder;

            AppProtectionState state = currentState(w);
            holder.mAppStateValue.setText(describeStateAndRoute(state));
            if (PausedApps.isPaused(mContext, mAppId)) {
                int remainingMinutes = PausedApps.getRemainingMinutes(mContext, mAppId);
                holder.mAppStateHint.setText(mContext.getResources().getQuantityString(
                        R.plurals.protection_paused_resumes, remainingMinutes, remainingMinutes));
                holder.mAppStateHint.setTextColor(ContextCompat.getColor(mContext, R.color.colorAccent));
            } else {
                holder.mAppStateHint.setText(R.string.protection_app_not_working);
                holder.mAppStateHint.setTextColor(ContextCompat.getColor(mContext, R.color.colorPrimary));
            }
            holder.mRowAppState.setOnClickListener(v -> {
                Intent intent = new Intent(mContext, ProtectionActivity.class);
                intent.putExtra(DetailsActivity.INTENT_EXTRA_APP_PACKAGENAME, mAppId);
                intent.putExtra(DetailsActivity.INTENT_EXTRA_APP_UID, mAppUid);
                intent.putExtra(DetailsActivity.INTENT_EXTRA_APP_NAME, getAppName());
                mContext.startActivity(intent);
            });

            bindLibrariesRow(holder);
        }
    }

    /**
     * The one-line summary of how this app's traffic is handled. Protection and
     * remote-VPN routing stay separate choices (#723), but they read as one
     * sentence here and are both changed on the same screen.
     */
    private String describeStateAndRoute(AppProtectionState state) {
        String stateLabel = mContext.getString(stateLabelRes(state));
        String route = routeSummary();
        if (route == null)
            return stateLabel;

        return mContext.getString(R.string.protection_state_with_route, stateLabel, route);
    }

    /**
     * How this app reaches the network, or null when there is no remote VPN to
     * choose between — in which case saying "directly from this device" would
     * add a word without adding a choice.
     */
    @Nullable
    private String routeSummary() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        boolean wgEnabled = prefs.getBoolean("wg_enabled", false)
                && !TextUtils.isEmpty(prefs.getString("wg_config", ""));
        boolean applyApp = apply.getBoolean(mAppId, true);
        boolean defaultRoutes = wgEnabled && RemoteRoutingHelper.hasDefaultRoutes(mContext, prefs);

        if (RemoteRoutingLogic.getUnavailableReason(wgEnabled, defaultRoutes, applyApp) != null)
            return null;

        String mode = RemoteRoutingLogic.normalizeMode(
                prefs.getString(Rule.PREF_WG_ROUTE_MODE, RemoteRoutingLogic.getDefaultMode()));
        boolean tunnelled = RemoteRoutingLogic.routesThroughTunnel(
                mode, RemoteRoutingHelper.getRouteOverride(mContext, mAppId), true);
        return mContext.getString(tunnelled
                ? R.string.app_route_through : R.string.app_route_direct);
    }

    /**
     * The tracker-library summary. The report itself is a separate screen: it is
     * derived from the app's code rather than from the traffic listed below.
     */
    private void bindLibrariesRow(VHHeader holder) {
        holder.mAppLibrariesValue.setText(librarySummary());
        holder.mRowAppLibraries.setOnClickListener(v -> {
            Intent intent = new Intent(mContext, LibrariesActivity.class);
            intent.putExtra(DetailsActivity.INTENT_EXTRA_APP_PACKAGENAME, mAppId);
            intent.putExtra(DetailsActivity.INTENT_EXTRA_APP_NAME, getAppName());
            mContext.startActivity(intent);
        });
    }

    private String librarySummary() {
        TrackerAnalysisManager manager = TrackerAnalysisManager.getInstance(mContext);
        String cached = manager.getCachedResult(mAppId);

        if (mAnalysisWork != null && !mAnalysisWork.getState().isFinished())
            return mContext.getString(R.string.libraries_analysing);

        if (cached == null) {
            if (mAnalysisWork != null && mAnalysisWork.getState() == WorkInfo.State.FAILED)
                return mContext.getString(R.string.libraries_analysis_failed);
            return mContext.getString(R.string.libraries_not_analysed);
        }

        int count = TrackerAnalysisManager.countTrackers(cached);
        String summary = count == 0
                ? mContext.getString(R.string.libraries_none_found)
                : mContext.getResources().getQuantityString(R.plurals.libraries_found, count, count);

        // A stale result is still worth showing; it just needs the caveat.
        if (manager.isCacheStale(mAppId))
            summary = mContext.getString(R.string.libraries_outdated_suffix, summary);

        return summary;
    }

    private String getAppName() {
        try {
            return mContext.getPackageManager()
                    .getApplicationLabel(mContext.getPackageManager().getApplicationInfo(mAppId, 0))
                    .toString();
        } catch (PackageManager.NameNotFoundException ex) {
            return mAppId;
        }
    }

    private AppProtectionState currentState(InternetBlocklist w) {
        return AppProtectionState.resolve(
                apply.getBoolean(mAppId, true),
                BlockingMode.isTrackerProtectionEnabled(mContext, tracker_protect, mAppId),
                w.blockedInternet(mAppUid));
    }

    private static int stateLabelRes(AppProtectionState state) {
        switch (state) {
            case TRACKERS_ALLOWED:
                return R.string.app_state_trackers_allowed;
            case NO_INTERNET:
                return R.string.app_state_no_internet;
            case BYPASSED:
                return R.string.app_state_bypassed;
            case PROTECTED:
            default:
                return R.string.app_state_protected;
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
        final MaterialSwitch mSwitchTracker;
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
        final View mRowAppState;
        final TextView mAppStateValue;
        final TextView mAppStateHint;
        final View mRowAppLibraries;
        final TextView mAppLibrariesValue;

        VHHeader(View view) {
            super(view);
            mRowAppState = view.findViewById(R.id.rowAppState);
            mAppStateValue = view.findViewById(R.id.tvAppStateValue);
            mAppStateHint = view.findViewById(R.id.tvAppStateHint);
            mRowAppLibraries = view.findViewById(R.id.rowAppLibraries);
            mAppLibrariesValue = view.findViewById(R.id.tvAppLibrariesValue);
        }
    }
}
