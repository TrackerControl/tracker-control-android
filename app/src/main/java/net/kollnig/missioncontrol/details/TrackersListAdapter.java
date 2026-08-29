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
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.materialswitch.MaterialSwitch;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
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
import net.kollnig.missioncontrol.data.InternetBlocklist;
import net.kollnig.missioncontrol.data.PausedApps;
import net.kollnig.missioncontrol.data.RemoteRoutingLogic;
import net.kollnig.missioncontrol.data.Tracker;
import net.kollnig.missioncontrol.data.TrackerBlocklist;
import net.kollnig.missioncontrol.data.TrackerCategory;
import net.kollnig.missioncontrol.data.TrackerFeedLogic;
import net.kollnig.missioncontrol.data.TrackerList;
import net.kollnig.missioncontrol.data.TrackerStatusLogic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import eu.faircode.netguard.Rule;
import eu.faircode.netguard.Util;

/**
 * {@link RecyclerView.Adapter} for the flat per-app tracker feed.
 */
public class TrackersListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int TYPE_HEADER = 0;
    private static final int TYPE_SECTION = 1;
    private static final int TYPE_COMPANY = 2;
    private static final int TYPE_SHOW_MORE = 3;

    private final Integer mAppUid;
    private final String mAppId;
    private final String mAppName;
    private final Context mContext;
    private final SharedPreferences apply;
    private final SharedPreferences tracker_protect;
    private final SharedPreferences minimalOnlyPrefs;
    private List<TrackerCategory> mCategories = new ArrayList<>();
    private List<Object> mRows = new ArrayList<>();
    private final Set<String> mExpandedCompanyKeys = new HashSet<>();
    private final Set<String> mExpandedSections = new HashSet<>();

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
        mAppName = resolveAppName();

        apply = mContext.getSharedPreferences("apply", Context.MODE_PRIVATE);
        tracker_protect = mContext.getSharedPreferences("tracker_protect", Context.MODE_PRIVATE);
        minimalOnlyPrefs = mContext.getSharedPreferences("tracker_essential", Context.MODE_PRIVATE);

        // Removes blinks
        ((SimpleItemAnimator) Objects.requireNonNull(v.getItemAnimator())).setSupportsChangeAnimations(false);
    }

    public void set(List<TrackerCategory> items) {
        mCategories = items == null ? new ArrayList<>() : new ArrayList<>(items);
        rebuildRows();
        notifyDataSetChanged();
    }

    private void rebuildRows() {
        mRows = TrackerFeedLogic.buildRows(mCategories, mExpandedCompanyKeys, mExpandedSections);
    }

    private void rebuildRowsAndNotify() {
        rebuildRows();
        notifyDataSetChanged();
    }

    @Override
    @NonNull
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_SECTION) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_tracker_feed_section, parent, false);
            return new VHSection(view);
        } else if (viewType == TYPE_COMPANY) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_tracker_feed_company, parent, false);
            return new VHCompany(view);
        } else if (viewType == TYPE_SHOW_MORE) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_tracker_feed_show_more, parent, false);
            return new VHShowMore(view);
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
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof VHHeader) {
            bindHeader((VHHeader) holder);
            return;
        }

        Object row = mRows.get(position - 1);
        if (holder instanceof VHSection && row instanceof TrackerFeedLogic.SectionRow) {
            bindSection((VHSection) holder, ((TrackerFeedLogic.SectionRow) row).category);
        } else if (holder instanceof VHCompany && row instanceof TrackerFeedLogic.CompanyRow) {
            bindCompany((VHCompany) holder, (TrackerFeedLogic.CompanyRow) row);
        } else if (holder instanceof VHShowMore && row instanceof TrackerFeedLogic.ShowMoreRow) {
            bindShowMore((VHShowMore) holder, (TrackerFeedLogic.ShowMoreRow) row);
        }
    }

    private void bindSection(VHSection holder, TrackerCategory category) {
        final InternetBlocklist w = InternetBlocklist.getInstance(mContext);
        final TrackerBlocklist b = TrackerBlocklist.getInstance(mContext);
        final String categoryName = category.getCategoryName();
        final String categoryDisplayName = category.getDisplayName(mContext);

        holder.mSectionTitle.setText(categoryDisplayName);
        if (category.lastSeen != null && category.lastSeen != 0) {
            holder.mSectionTime.setVisibility(View.VISIBLE);
            holder.mSectionTime.setText(String.format(mContext.getString(R.string.feed_section_last_contact),
                    Util.relativeTime(category.lastSeen)));
        } else {
            holder.mSectionTime.setText("");
            holder.mSectionTime.setVisibility(View.GONE);
        }

        // Reset conditional state before applying this category's state.
        holder.mSectionExplainer.setVisibility(View.GONE);
        holder.mSwitchSection.setVisibility(View.VISIBLE);
        holder.mSwitchSection.setOnCheckedChangeListener(null);

        boolean trackerProtectionEnabled = BlockingMode.isTrackerProtectionEnabled(
                mContext, tracker_protect, mAppId);
        boolean minimal = BlockingMode.isMinimalMode(mContext);
        AppProtectionState state = currentState(w);
        boolean readOnlyMinimal = minimal || state == AppProtectionState.MINIMAL_ONLY;
        boolean categoryBlocked = b.blocked(mAppUid, categoryName);

        boolean categoryMinimallyBlocked = false;
        if (readOnlyMinimal) {
            for (Tracker tracker : category.getChildren()) {
                if (TrackerList.isMinimallyBlocked(tracker)) {
                    categoryMinimallyBlocked = true;
                    break;
                }
            }
        }
        boolean trackerPolicyActive = state == AppProtectionState.PROTECTED
                || state == AppProtectionState.MINIMAL_ONLY;
        boolean categoryEffectivelyBlocked = state == AppProtectionState.NO_INTERNET
                || (trackerProtectionEnabled && trackerPolicyActive
                && (readOnlyMinimal ? categoryMinimallyBlocked : categoryBlocked));

        if (TrackerBlocklist.NECESSARY_CATEGORY.equals(categoryName)) {
            holder.mSectionExplainer.setVisibility(View.VISIBLE);
            holder.mSectionExplainer.setText(mContext.getString(categoryEffectivelyBlocked
                    ? R.string.feed_essential_explainer_blocked
                    : R.string.feed_essential_explainer, mAppName));
        }

        holder.mSwitchSection.setContentDescription(
                String.format(mContext.getString(R.string.toggle_block_category_description),
                        categoryDisplayName));
        if (readOnlyMinimal) {
            holder.mSwitchSection.setEnabled(false);
            holder.mSwitchSection.setChecked(trackerProtectionEnabled && categoryMinimallyBlocked);
        } else {
            boolean enabled = state == AppProtectionState.PROTECTED;
            holder.mSwitchSection.setEnabled(enabled);
            holder.mSwitchSection.setChecked(categoryBlocked);
            if (enabled) {
                holder.mSwitchSection.setOnCheckedChangeListener((buttonView, hasBecomeChecked) -> {
                    if (!buttonView.isPressed())
                        return;

                    if (hasBecomeChecked)
                        b.block(mAppUid, categoryName);
                    else {
                        b.unblock(mAppUid, categoryName);
                        Toast.makeText(mContext, R.string.category_unblocked, Toast.LENGTH_SHORT).show();
                    }

                    rebuildRowsAndNotify();
                });
            }
        }
    }

    private void bindCompany(VHCompany holder, TrackerFeedLogic.CompanyRow row) {
        final InternetBlocklist w = InternetBlocklist.getInstance(mContext);
        final TrackerBlocklist b = TrackerBlocklist.getInstance(mContext);
        final Tracker tracker = row.tracker;
        final String categoryName = row.categoryName;
        final String blockingKey = TrackerBlocklist.getBlockingKey(tracker);
        final boolean trackerProtectionEnabled = BlockingMode.isTrackerProtectionEnabled(
                mContext, tracker_protect, mAppId);
        final boolean minimal = BlockingMode.isMinimalMode(mContext);
        final AppProtectionState state = currentState(w);
        final boolean categoryBlocked = b.blocked(mAppUid, categoryName);
        final boolean companyKeyBlocked = b.blocked(mAppUid, blockingKey);
        final TrackerStatusLogic.Result status = TrackerStatusLogic.resolve(
                trackerProtectionEnabled,
                minimal,
                BlockingMode.isStrictMode(mContext),
                state,
                TrackerList.isMinimallyBlocked(tracker),
                TrackerList.isMinimallyKnown(tracker),
                categoryBlocked,
                companyKeyBlocked,
                tracker.isAllowedInStandardMode());

        String companyName = tracker.getName();
        if (TRACKER_HOSTLIST.equals(companyName))
            companyName = mContext.getString(R.string.tracker_hostlist);
        holder.mCompany.setText(companyName);
        if (tracker.lastSeen != null && tracker.lastSeen != 0) {
            holder.mLastSeen.setVisibility(View.VISIBLE);
            holder.mLastSeen.setText(Util.relativeTime(tracker.lastSeen));
        } else {
            holder.mLastSeen.setText("");
            holder.mLastSeen.setVisibility(View.GONE);
        }
        holder.mStatus.setText(statusString(status.status));
        holder.mStatus.setTextColor(ContextCompat.getColor(mContext,
                status.status == TrackerStatusLogic.Status.BLOCKED
                        ? R.color.colorPrimary : R.color.colorAccent));
        holder.mExpand.setImageLevel(row.expanded ? 1 : 0);
        ViewCompat.setStateDescription(holder.itemView, mContext.getString(row.expanded
                ? R.string.feed_company_expanded : R.string.feed_company_collapsed));

        // Reset every conditional child so recycled holders cannot leak the
        // previous row's expansion, uncertainty, or shared-IP explanation.
        holder.mLayoutExpanded.setVisibility(row.expanded ? View.VISIBLE : View.GONE);
        holder.mUncertainNote.setVisibility(row.expanded && tracker.isUncertain()
                ? View.VISIBLE : View.GONE);
        holder.mSharedIpNote.setVisibility(View.GONE);
        holder.mSwitchAllowCompany.setVisibility(View.VISIBLE);
        holder.mSwitchAllowCompany.setOnCheckedChangeListener(null);

        if (row.expanded) {
            List<String> sortedHosts = new ArrayList<>(tracker.getHosts());
            Collections.sort(sortedHosts);
            holder.mHosts.setText(TextUtils.join("\n", sortedHosts));

            if (status.status == TrackerStatusLogic.Status.ALLOWED_SHARED_IP) {
                holder.mSharedIpNote.setText(R.string.allowed_shared_ip);
                holder.mSharedIpNote.setVisibility(View.VISIBLE);
                holder.mSwitchAllowCompany.setVisibility(View.GONE);
            } else {
                if (status.status == TrackerStatusLogic.Status.MONITORED) {
                    holder.mSharedIpNote.setText(R.string.tracker_monitored_minimal);
                    holder.mSharedIpNote.setVisibility(View.VISIBLE);
                } else if (status.status == TrackerStatusLogic.Status.ALLOWED
                        && state == AppProtectionState.PROTECTED
                        && !categoryBlocked) {
                    holder.mSharedIpNote.setText(R.string.category_unblocked_warning);
                    holder.mSharedIpNote.setVisibility(View.VISIBLE);
                }
                String displayName = tracker.getName();
                if (TRACKER_HOSTLIST.equals(displayName))
                    displayName = mContext.getString(R.string.tracker_hostlist);
                holder.mSwitchAllowCompany.setText(String.format(
                        mContext.getString(R.string.feed_allow_company_in_app), displayName, mAppName));
                // The switch represents effective access, not only the raw
                // company key. This matters when a stale key survives a
                // category whitelist.
                holder.mSwitchAllowCompany.setChecked(status.status != TrackerStatusLogic.Status.BLOCKED);
                boolean enabled = status.interactivity == TrackerStatusLogic.Interactivity.TOGGLEABLE
                        && state == AppProtectionState.PROTECTED
                        && categoryBlocked
                        && !w.blockedInternet(mAppUid);
                holder.mSwitchAllowCompany.setEnabled(enabled);
                if (status.interactivity == TrackerStatusLogic.Interactivity.TOGGLEABLE) {
                    holder.mSwitchAllowCompany.setOnCheckedChangeListener((buttonView, hasBecomeChecked) -> {
                        if (!buttonView.isPressed())
                            return;

                        if (hasBecomeChecked)
                            b.unblock(mAppUid, tracker);
                        else
                            b.block(mAppUid, tracker);

                        rebuildRowsAndNotify();
                    });
                }
            }
        } else {
            holder.mHosts.setText("");
            holder.mSwitchAllowCompany.setText("");
            holder.mSwitchAllowCompany.setChecked(false);
            holder.mSwitchAllowCompany.setEnabled(false);
        }

        holder.itemView.setOnClickListener(v -> {
            String key = TrackerBlocklist.getBlockingKey(tracker);
            if (mExpandedCompanyKeys.contains(key))
                mExpandedCompanyKeys.remove(key);
            else
                mExpandedCompanyKeys.add(key);
            rebuildRowsAndNotify();
        });
    }

    private int statusString(TrackerStatusLogic.Status status) {
        switch (status) {
            case BLOCKED:
                return R.string.timeline_tracker_blocked;
            case ALLOWED_BY_USER:
                return R.string.feed_allowed_by_you;
            case ALLOWED_SHARED_IP:
                return R.string.feed_allowed_shared_ip;
            case MONITORED:
                return R.string.feed_monitored;
            case ALLOWED:
            default:
                return R.string.timeline_tracker_allowed;
        }
    }

    private void bindShowMore(VHShowMore holder, TrackerFeedLogic.ShowMoreRow row) {
        holder.mShowMore.setText(mContext.getResources().getQuantityString(
                R.plurals.feed_show_more_companies, row.hiddenCount, row.hiddenCount));
        holder.itemView.setOnClickListener(v -> {
            mExpandedSections.add(row.categoryName);
            rebuildRowsAndNotify();
        });
    }

    private void bindHeader(VHHeader holder) {
        final InternetBlocklist w = InternetBlocklist.getInstance(mContext);
        AppProtectionState state = currentState(w);
        holder.mAppStateTitle.setText(state == AppProtectionState.PROTECTED
                ? protectedTitleRes() : stateLabelRes(state));

        String route = routeSummary();
        if (route == null) {
            holder.mAppStateValue.setText("");
            holder.mAppStateValue.setVisibility(View.GONE);
        } else {
            holder.mAppStateValue.setText(route);
            holder.mAppStateValue.setVisibility(View.VISIBLE);
        }

        if (PausedApps.isPaused(mContext, mAppId)) {
            int remainingMinutes = PausedApps.getRemainingMinutes(mContext, mAppId);
            holder.mAppStateHint.setText(mContext.getResources().getQuantityString(
                    R.plurals.protection_paused_resumes, remainingMinutes, remainingMinutes));
            holder.mAppStateHint.setTextColor(ContextCompat.getColor(mContext, R.color.colorAccent));
        } else if (state == AppProtectionState.PROTECTED) {
            if (BlockingMode.isMinimalMode(mContext))
                holder.mAppStateHint.setText(R.string.protection_app_not_working);
            else
                holder.mAppStateHint.setText(R.string.app_state_subtitle_misbehaving);
            holder.mAppStateHint.setTextColor(ContextCompat.getColor(mContext, R.color.colorPrimary));
        } else {
            holder.mAppStateHint.setText(R.string.protection_app_not_working);
            holder.mAppStateHint.setTextColor(ContextCompat.getColor(mContext, R.color.colorPrimary));
        }
        holder.mRowAppState.setOnClickListener(v -> {
            Intent intent = new Intent(mContext, ProtectionActivity.class);
            intent.putExtra(DetailsActivity.INTENT_EXTRA_APP_PACKAGENAME, mAppId);
            intent.putExtra(DetailsActivity.INTENT_EXTRA_APP_UID, mAppUid);
            intent.putExtra(DetailsActivity.INTENT_EXTRA_APP_NAME, mAppName);
            mContext.startActivity(intent);
        });

        bindLibrariesRow(holder);
    }

    private int protectedTitleRes() {
        if (BlockingMode.isMinimalMode(mContext))
            return R.string.app_state_title_minimal;
        if (BlockingMode.isStrictMode(mContext))
            return R.string.app_state_title_strict;
        return R.string.app_state_title_standard;
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
     * The tracker-library summary. The report itself is a separate screen: it
     * is derived from the app's code rather than from the traffic listed below.
     */
    private void bindLibrariesRow(VHHeader holder) {
        holder.mAppLibrariesValue.setText(librarySummary());
        holder.mRowAppLibraries.setOnClickListener(v -> {
            Intent intent = new Intent(mContext, LibrariesActivity.class);
            intent.putExtra(DetailsActivity.INTENT_EXTRA_APP_PACKAGENAME, mAppId);
            intent.putExtra(DetailsActivity.INTENT_EXTRA_APP_NAME, mAppName);
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

    private String resolveAppName() {
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
                w.blockedInternet(mAppUid),
                BlockingMode.isMinimalOnlyApp(mContext, minimalOnlyPrefs, mAppId));
    }

    private static int stateLabelRes(AppProtectionState state) {
        switch (state) {
            case MINIMAL_ONLY:
                return R.string.app_state_essential_only;
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
        return mRows.size() + 1;
    }

    @Override
    public int getItemViewType(int position) {
        if (position == 0)
            return TYPE_HEADER;

        Object row = mRows.get(position - 1);
        if (row instanceof TrackerFeedLogic.SectionRow)
            return TYPE_SECTION;
        if (row instanceof TrackerFeedLogic.CompanyRow)
            return TYPE_COMPANY;
        if (row instanceof TrackerFeedLogic.ShowMoreRow)
            return TYPE_SHOW_MORE;
        throw new IllegalStateException("Unknown tracker feed row: " + row);
    }

    static class VHSection extends RecyclerView.ViewHolder {
        final TextView mSectionTitle;
        final TextView mSectionTime;
        final TextView mSectionExplainer;
        final MaterialSwitch mSwitchSection;

        VHSection(View view) {
            super(view);
            mSectionTitle = view.findViewById(R.id.tvSection);
            mSectionTime = view.findViewById(R.id.tvSectionTime);
            mSectionExplainer = view.findViewById(R.id.tvSectionExplainer);
            mSwitchSection = view.findViewById(R.id.switchSection);
        }
    }

    static class VHCompany extends RecyclerView.ViewHolder {
        final TextView mCompany;
        final TextView mLastSeen;
        final TextView mStatus;
        final ImageView mExpand;
        final View mLayoutExpanded;
        final TextView mHosts;
        final TextView mUncertainNote;
        final MaterialSwitch mSwitchAllowCompany;
        final TextView mSharedIpNote;

        VHCompany(View view) {
            super(view);
            mCompany = view.findViewById(R.id.tvCompany);
            mLastSeen = view.findViewById(R.id.tvLastSeen);
            mStatus = view.findViewById(R.id.tvStatus);
            mExpand = view.findViewById(R.id.ivExpand);
            mLayoutExpanded = view.findViewById(R.id.layoutExpanded);
            mHosts = view.findViewById(R.id.tvHosts);
            mUncertainNote = view.findViewById(R.id.tvUncertainNote);
            mSwitchAllowCompany = view.findViewById(R.id.switchAllowCompany);
            mSharedIpNote = view.findViewById(R.id.tvSharedIpNote);
        }
    }

    static class VHShowMore extends RecyclerView.ViewHolder {
        final TextView mShowMore;

        VHShowMore(View view) {
            super(view);
            mShowMore = view.findViewById(R.id.tvShowMore);
        }
    }

    static class VHHeader extends RecyclerView.ViewHolder {
        final View mRowAppState;
        final TextView mAppStateTitle;
        final TextView mAppStateValue;
        final TextView mAppStateHint;
        final View mRowAppLibraries;
        final TextView mAppLibrariesValue;

        VHHeader(View view) {
            super(view);
            mRowAppState = view.findViewById(R.id.rowAppState);
            mAppStateTitle = view.findViewById(R.id.tvAppStateTitle);
            mAppStateValue = view.findViewById(R.id.tvAppStateValue);
            mAppStateHint = view.findViewById(R.id.tvAppStateHint);
            mRowAppLibraries = view.findViewById(R.id.rowAppLibraries);
            mAppLibrariesValue = view.findViewById(R.id.tvAppLibrariesValue);
        }
    }
}
