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
import android.widget.RadioGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;
import androidx.work.Data;
import androidx.work.WorkInfo;

import net.kollnig.missioncontrol.Common;
import net.kollnig.missioncontrol.R;
import net.kollnig.missioncontrol.analysis.TrackerAnalysisManager;
import net.kollnig.missioncontrol.analysis.TrackerAnalysisWorker;
import net.kollnig.missioncontrol.data.AppProtectionState;
import net.kollnig.missioncontrol.data.BlockingMode;
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
    private final SharedPreferences tracker_protect;
    private List<TrackerCategory> mValues = new ArrayList<>();

    // Analysis UI elements (populated when header is created)
    private TextView mBtnAnalyze;
    private TextView mTvDetectedTrackers;
    private TextView mTvDisclaimer;
    private View mLayoutProgress;
    private TextView mTvAnalysisProgress;
    private ProgressBar mPbTrackerDetection;

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

            // Setup button for on-demand tracker analysis
            setupTrackerAnalysisButton(view);

            return new VHHeader(view);
        }

        throw new RuntimeException(
                "there is no type that matches the type " + viewType + " + make sure your using types correctly");
    }

    /**
     * Setup button for on-demand tracker library analysis.
     * The Fragment is responsible for observing WorkManager and calling update
     * methods.
     *
     * @param view The tracker view to add the button handler to
     */
    private void setupTrackerAnalysisButton(View view) {
        mBtnAnalyze = view.findViewById(R.id.btnAnalyzeTrackers);
        mTvDetectedTrackers = view.findViewById(R.id.tvDetectedTrackers);
        mTvDisclaimer = view.findViewById(R.id.tvLibraryDisclaimer);
        mLayoutProgress = view.findViewById(R.id.layoutAnalysisProgress);
        mTvAnalysisProgress = view.findViewById(R.id.tvAnalysisProgress);
        mPbTrackerDetection = view.findViewById(R.id.pbDetectedTrackers);

        TrackerAnalysisManager manager = TrackerAnalysisManager.getInstance(mContext);

        // Show cached results initially
        String cachedResults = manager.getCachedResult(mAppId);
        if (cachedResults != null) {
            String res = String.format(mContext.getString(R.string.detected_trackers), cachedResults);
            if (manager.isCacheStale(mAppId)) {
                res += mContext.getString(R.string.analysis_outdated_version);
                mBtnAnalyze.setText(R.string.update_analysis);
            }
            mTvDetectedTrackers.setText(res);
            mTvDetectedTrackers.setVisibility(View.VISIBLE);
            mTvDisclaimer.setVisibility(View.VISIBLE);
        }

        mBtnAnalyze.setOnClickListener(v -> {
            manager.startAnalysis(mAppId);
            // Fragment's observer will pick up the work state changes
        });

        if (manager.shouldStartAnalysis(mAppId))
            manager.startAnalysis(mAppId);
    }

    /**
     * Called by Fragment when analysis state changes.
     */
    public void updateAnalysisState(WorkInfo workInfo) {
        if (mBtnAnalyze == null)
            return; // Header not yet created

        if (workInfo == null) {
            mLayoutProgress.setVisibility(View.GONE);
            mBtnAnalyze.setEnabled(true);
            return;
        }

        switch (workInfo.getState()) {
            case ENQUEUED:
            case BLOCKED:
                mBtnAnalyze.setEnabled(false);
                mLayoutProgress.setVisibility(View.VISIBLE);
                mTvAnalysisProgress.setText(R.string.analysis_queued);
                mPbTrackerDetection.setIndeterminate(true);
                mTvDetectedTrackers.setVisibility(View.GONE);
                mTvDisclaimer.setVisibility(View.GONE);
                break;

            case RUNNING:
                mBtnAnalyze.setEnabled(false);
                mLayoutProgress.setVisibility(View.VISIBLE);
                mPbTrackerDetection.setIndeterminate(false);
                mTvDetectedTrackers.setVisibility(View.GONE);
                mTvDisclaimer.setVisibility(View.GONE);

                Data progress = workInfo.getProgress();
                int percent = progress.getInt(TrackerAnalysisWorker.KEY_PROGRESS, 0);
                mPbTrackerDetection.setProgress(percent);
                mTvAnalysisProgress.setText(String.format(
                        mContext.getString(R.string.analyzing_classes_progress), percent));
                break;

            case SUCCEEDED:
                mLayoutProgress.setVisibility(View.GONE);
                mBtnAnalyze.setEnabled(true);
                mBtnAnalyze.setText(R.string.analyze_tracker_libraries);

                String result = workInfo.getOutputData().getString(TrackerAnalysisWorker.KEY_RESULT);
                if (result != null) {
                    String res = String.format(mContext.getString(R.string.detected_trackers), result);
                    mTvDetectedTrackers.setText(res);
                    mTvDetectedTrackers.setVisibility(View.VISIBLE);
                    mTvDisclaimer.setVisibility(View.VISIBLE);
                }
                break;

            case FAILED:
                mLayoutProgress.setVisibility(View.GONE);
                mBtnAnalyze.setEnabled(true);

                String error = workInfo.getOutputData().getString(TrackerAnalysisWorker.KEY_ERROR);
                if (error != null) {
                    mTvDetectedTrackers.setText(error);
                    mTvDetectedTrackers.setVisibility(View.VISIBLE);
                }
                break;

            case CANCELLED:
                mLayoutProgress.setVisibility(View.GONE);
                mBtnAnalyze.setEnabled(true);
                break;
        }
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

            holder.mLibraryExplanation.setText(R.string.trackers_static_explanation);

            // The internet block is keyed by UID, so it necessarily covers every
            // package sharing that UID. Say so rather than letting it surprise.
            String relatedApps = getRelatedApps();
            if (relatedApps == null) {
                holder.mNoInternetExplanation.setText(R.string.app_state_no_internet_explanation);
            } else {
                String explanation = mContext.getString(R.string.app_state_no_internet_explanation_shared,
                        mContext.getString(R.string.app_state_no_internet_explanation),
                        relatedApps);
                holder.mNoInternetExplanation.setText(explanation);
            }

            AppProtectionState state = currentState(w);
            holder.mAppState.setOnCheckedChangeListener(null);
            holder.mAppState.check(radioIdFor(state));
            holder.mAppState.setOnCheckedChangeListener((group, checkedId) -> {
                AppProtectionState selected = stateForRadioId(checkedId);
                if (selected == null || selected == currentState(w))
                    return;

                applyState(selected, w);
                notifyDataSetChanged();
            });
        }
    }

    /**
     * Comma-separated labels of the other packages sharing this app's UID, or
     * {@code null} when the UID belongs to this package alone.
     */
    @Nullable
    private String getRelatedApps() {
        PackageManager pm = mContext.getPackageManager();
        String[] packages = Util.getPackagesForUid(pm, mAppUid);
        if (packages == null || packages.length < 2)
            return null;

        List<String> names = new ArrayList<>();
        for (String packageName : packages) {
            if (packageName.equals(mAppId))
                continue;

            try {
                names.add(pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString());
            } catch (PackageManager.NameNotFoundException ignored) {
                names.add(packageName);
            }
        }

        return names.isEmpty() ? null : TextUtils.join(", ", names);
    }

    private AppProtectionState currentState(InternetBlocklist w) {
        return AppProtectionState.resolve(
                apply.getBoolean(mAppId, true),
                BlockingMode.isTrackerProtectionEnabled(mContext, tracker_protect, mAppId),
                w.blockedInternet(mAppUid));
    }

    private void applyState(AppProtectionState target, InternetBlocklist w) {
        AppProtectionState.Change change = AppProtectionState.of(target);

        boolean applyBefore = apply.getBoolean(mAppId, true);
        boolean protectBefore = BlockingMode.isTrackerProtectionEnabled(mContext, tracker_protect, mAppId);

        apply.edit().putBoolean(mAppId, change.apply).apply();
        // Only a re-included app leaves the mode-managed exclusion set. Clearing
        // it unconditionally would turn a Minimal-mode auto-exclusion into a
        // permanent one as soon as the user toggled the app off and on again.
        if (change.apply)
            BlockingMode.clearAutoExcludedApp(mContext, mAppId);

        if (change.trackerProtect != null)
            tracker_protect.edit().putBoolean(mAppId, change.trackerProtect).apply();

        w.apply(mContext, mAppUid, change.internetBlocked);

        // The internet blocklist is read live by the packet path, so a change
        // that only blocks or unblocks Internet needs no reload. Which apps are
        // in the tun, and which of them are filtered, are baked into the rules.
        boolean needsReload = change.apply != applyBefore
                || (change.trackerProtect != null && change.trackerProtect != protectBefore);
        if (!needsReload)
            return;

        // Move expensive operations off the main thread to prevent UI freezing
        // Rule.clearCache() can block waiting for a lock held by Rule.getRules()
        AsyncTask.execute(() -> {
            Rule.clearCache(mContext);
            ServiceSinkhole.reload("app protection changed", mContext, false);
        });
    }

    private static int radioIdFor(AppProtectionState state) {
        switch (state) {
            case TRACKERS_ALLOWED:
                return R.id.rbStateTrackersAllowed;
            case NO_INTERNET:
                return R.id.rbStateNoInternet;
            case BYPASSED:
                return R.id.rbStateBypassed;
            case PROTECTED:
            default:
                return R.id.rbStateProtected;
        }
    }

    @Nullable
    private static AppProtectionState stateForRadioId(int checkedId) {
        if (checkedId == R.id.rbStateProtected)
            return AppProtectionState.PROTECTED;
        if (checkedId == R.id.rbStateTrackersAllowed)
            return AppProtectionState.TRACKERS_ALLOWED;
        if (checkedId == R.id.rbStateNoInternet)
            return AppProtectionState.NO_INTERNET;
        if (checkedId == R.id.rbStateBypassed)
            return AppProtectionState.BYPASSED;
        return null;
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
        final TextView mLibraryExplanation;
        final TextView mLibraryDisclaimer;
        final RadioGroup mAppState;
        final TextView mNoInternetExplanation;

        VHHeader(View view) {
            super(view);
            mLibraryExplanation = view.findViewById(R.id.tvLibraryExplanation);
            mLibraryDisclaimer = view.findViewById(R.id.tvLibraryDisclaimer);
            mAppState = view.findViewById(R.id.rgAppState);
            mNoInternetExplanation = view.findViewById(R.id.tvStateNoInternetDesc);
        }
    }
}
