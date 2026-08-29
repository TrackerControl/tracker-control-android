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
import android.util.Log;
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
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.materialswitch.MaterialSwitch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;
import androidx.work.Data;
import androidx.work.WorkInfo;

import net.kollnig.missioncontrol.Common;
import net.kollnig.missioncontrol.DetailsActivity;
import net.kollnig.missioncontrol.R;
import net.kollnig.missioncontrol.analysis.TrackerAnalysisManager;
import net.kollnig.missioncontrol.analysis.TrackerAnalysisWorker;
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

    // At most one of the app-controls bottom sheets is open at a time.
    private BottomSheetDialog mOpenSheet;

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

            AppProtectionState state = currentState(w);
            holder.mAppStateValue.setText(stateLabelRes(state));
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

            bindRemoteRouting(holder);
        }
    }

    /**
     * The remote-routing control, which is deliberately independent of the
     * protection state above: whether an app is filtered and whether it is
     * forwarded through the remote VPN are separate choices (#723).
     */
    private void bindRemoteRouting(VHHeader holder) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        boolean wgEnabled = prefs.getBoolean("wg_enabled", false)
                && !TextUtils.isEmpty(prefs.getString("wg_config", ""));
        boolean applyApp = apply.getBoolean(mAppId, true);
        boolean defaultRoutes = wgEnabled && hasDefaultRoutes(prefs);

        RemoteRoutingLogic.Unavailable unavailable =
                RemoteRoutingLogic.getUnavailableReason(wgEnabled, defaultRoutes, applyApp);
        if (unavailable != null) {
            holder.mRowAppRoute.setOnClickListener(null);
            holder.mRowAppRoute.setClickable(false);
            holder.mRowAppRoute.setEnabled(false);
            holder.mAppRouteChevron.setVisibility(View.GONE);
            holder.mAppRouteValue.setText(explainUnavailable(unavailable));
            return;
        }

        String mode = RemoteRoutingLogic.normalizeMode(
                prefs.getString(Rule.PREF_WG_ROUTE_MODE, RemoteRoutingLogic.getDefaultMode()));
        boolean tunnelled = RemoteRoutingLogic.routesThroughTunnel(mode, getRouteOverride(), true);

        holder.mRowAppRoute.setEnabled(true);
        holder.mRowAppRoute.setClickable(true);
        holder.mAppRouteChevron.setVisibility(View.VISIBLE);
        holder.mAppRouteValue.setText(tunnelled ? R.string.app_route_through : R.string.app_route_direct);
        holder.mRowAppRoute.setOnClickListener(v -> showRouteSheet());
    }

    /**
     * Shows the remote-routing bottom sheet, recomputing the current mode the
     * same way {@link #bindRemoteRouting(VHHeader)} does.
     */
    private void showRouteSheet() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        String mode = RemoteRoutingLogic.normalizeMode(
                prefs.getString(Rule.PREF_WG_ROUTE_MODE, RemoteRoutingLogic.getDefaultMode()));
        boolean tunnelled = RemoteRoutingLogic.routesThroughTunnel(mode, getRouteOverride(), true);

        BottomSheetDialog sheet = new BottomSheetDialog(mContext);
        View view = LayoutInflater.from(mContext).inflate(R.layout.bottom_sheet_app_route, null);
        sheet.setContentView(view);

        RadioGroup rgAppRoute = view.findViewById(R.id.rgAppRoute);
        rgAppRoute.check(tunnelled ? R.id.rbRouteTunnel : R.id.rbRouteDirect);
        rgAppRoute.setOnCheckedChangeListener((group, checkedId) -> {
            boolean wantsTunnel = (checkedId == R.id.rbRouteTunnel);

            // Dismiss first: the reload triggered below shouldn't hold the
            // sheet open while it runs.
            sheet.dismiss();

            if (wantsTunnel != RemoteRoutingLogic.routesThroughTunnel(mode, getRouteOverride(), true)) {
                mContext.getSharedPreferences(Rule.PREF_WG_ROUTE, Context.MODE_PRIVATE)
                        .edit().putBoolean(mAppId, wantsTunnel).apply();

                AsyncTask.execute(() -> {
                    Rule.clearCache(mContext);
                    ServiceSinkhole.reload("app routing changed", mContext, false);
                });

                // The row subtitle now depends on this value.
                notifyDataSetChanged();
            }
        });

        showSheet(sheet);
    }

    /**
     * Only one sheet should be open at a time, and it must not outlive the
     * RecyclerView that hosts the row that opened it.
     */
    private void showSheet(BottomSheetDialog sheet) {
        if (mOpenSheet != null)
            mOpenSheet.dismiss();

        mOpenSheet = sheet;
        sheet.setOnDismissListener(d -> {
            if (mOpenSheet == d)
                mOpenSheet = null;
        });
        sheet.show();
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);

        if (mOpenSheet != null) {
            mOpenSheet.dismiss();
            mOpenSheet = null;
        }
    }

    @Nullable
    private Boolean getRouteOverride() {
        SharedPreferences wgRoute = mContext.getSharedPreferences(Rule.PREF_WG_ROUTE,
                Context.MODE_PRIVATE);
        return wgRoute.contains(mAppId) ? wgRoute.getBoolean(mAppId, true) : null;
    }

    private boolean hasDefaultRoutes(SharedPreferences prefs) {
        try {
            net.kollnig.missioncontrol.wg.WgConfig config =
                    net.kollnig.missioncontrol.wg.WgConfigParser.INSTANCE
                            .parse(prefs.getString("wg_config", ""));
            List<String> allowedIps = new ArrayList<>();
            for (net.kollnig.missioncontrol.wg.WgPeer peer : config.getPeers())
                allowedIps.addAll(peer.getAllowedIPs());
            return RemoteRoutingLogic.hasDefaultRoutes(allowedIps,
                    prefs.getBoolean("ip6", true));
        } catch (Throwable ex) {
            Log.w(TAG, "Cannot read AllowedIPs, hiding per-app routing: " + ex);
            return false;
        }
    }

    private String explainUnavailable(RemoteRoutingLogic.Unavailable unavailable) {
        switch (unavailable) {
            case BYPASSED:
                return mContext.getString(R.string.app_route_unavailable_bypassed);
            case PARTIAL_ROUTES:
                return mContext.getString(R.string.app_route_unavailable_partial_routes);
            case NO_REMOTE_VPN:
            default:
                return mContext.getString(R.string.app_route_unavailable_no_vpn);
        }
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
        final TextView mLibraryExplanation;
        final TextView mLibraryDisclaimer;
        final View mRowAppState;
        final TextView mAppStateValue;
        final TextView mAppStateHint;
        final View mRowAppRoute;
        final TextView mAppRouteValue;
        final View mAppRouteChevron;

        VHHeader(View view) {
            super(view);
            mLibraryExplanation = view.findViewById(R.id.tvLibraryExplanation);
            mLibraryDisclaimer = view.findViewById(R.id.tvLibraryDisclaimer);
            mRowAppState = view.findViewById(R.id.rowAppState);
            mAppStateValue = view.findViewById(R.id.tvAppStateValue);
            mAppStateHint = view.findViewById(R.id.tvAppStateHint);
            mRowAppRoute = view.findViewById(R.id.rowAppRoute);
            mAppRouteValue = view.findViewById(R.id.tvAppRouteValue);
            mAppRouteChevron = view.findViewById(R.id.ivAppRouteChevron);
        }
    }
}
