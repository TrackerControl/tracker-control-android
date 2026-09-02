package net.kollnig.missioncontrol;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Pair;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.compose.ui.platform.ComposeView;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import net.kollnig.missioncontrol.data.InsightsData;
import net.kollnig.missioncontrol.data.InsightsDataProvider;
import net.kollnig.missioncontrol.data.TimelineEntry;
import net.kollnig.missioncontrol.data.Tracker;
import net.kollnig.missioncontrol.data.TrackerContact;
import net.kollnig.missioncontrol.data.TrackerList;
import net.kollnig.missioncontrol.ui.compose.TimelineEmptyState;
import net.kollnig.missioncontrol.ui.compose.TimelineScreen;
import net.kollnig.missioncontrol.ui.compose.TimelineScreenCallbacks;
import net.kollnig.missioncontrol.ui.compose.TimelineScreenController;

import java.io.File;
import java.io.FileOutputStream;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import eu.faircode.netguard.DatabaseHelper;
import eu.faircode.netguard.Util;

public class TimelineFragment extends Fragment {

    private static final String TAG = "TimelineFragment";
    private static final long REFRESH_DEBOUNCE_MS = 500L;
    // Catch stale relative timestamps and any missed AccessChangedListener
    // callbacks by re-querying on a slow tick while the screen is open.
    private static final long PERIODIC_REFRESH_MS = 30_000L;

    @Nullable
    private ComposeView composeView;
    @Nullable
    private TimelineScreenController screenController;
    @Nullable
    private InsightsData insightsData;
    private int viewGeneration;
    // Monotonically increasing per loader, so a request started earlier (but
    // finishing later, e.g. after a subsequent pull-to-refresh) never
    // overwrites a result from a request that was started after it.
    private int timelineRequestId;
    private int insightsRequestId;

    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshRunnable = this::refreshAll;
    private final Runnable periodicRunnable = new Runnable() {
        @Override
        public void run() {
            refreshAll();
            refreshHandler.postDelayed(this, PERIODIC_REFRESH_MS);
        }
    };
    private final DatabaseHelper.AccessChangedListener accessListener =
            () -> {
                refreshHandler.removeCallbacks(refreshRunnable);
                refreshHandler.postDelayed(refreshRunnable, REFRESH_DEBOUNCE_MS);
            };

    @Nullable
    private ExecutorService lifecycleExecutor;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        ComposeView view = new ComposeView(requireContext());
        // A stable ID gives Compose's saveable state registry a key to persist
        // under, so the LazyColumn scroll position survives recreation.
        view.setId(R.id.compose_timeline);
        view.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        composeView = view;
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        final int generation = ++viewGeneration;
        composeView = (ComposeView) view;
        screenController = TimelineScreen.install(
                composeView,
                new TimelineScreenCallbacks() {
                    @Override
                    public void onEntryClick(int uid, String appName, String packageName) {
                        openEntry(uid, appName, packageName);
                    }

                    @Override
                    public void onOpenApp() {
                        Intent home = new Intent(Intent.ACTION_MAIN);
                        home.addCategory(Intent.CATEGORY_HOME);
                        home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(home);
                    }

                    @Override
                    public void onOpenSettings() {
                        startActivity(new Intent(requireContext(),
                                eu.faircode.netguard.ActivitySettings.class));
                    }

                    @Override
                    public void onOpenInsights() {
                        startActivity(new Intent(requireContext(), InsightsActivity.class));
                    }

                    @Override
                    public void onShareInsights() {
                        shareInsights(generation);
                    }

                    @Override
                    public void onDismissHint() {
                        SharedPreferences prefs = PreferenceManager
                                .getDefaultSharedPreferences(requireContext());
                        prefs.edit().putBoolean("hint_timeline_tap_entry", false).apply();
                        if (isCurrentView(generation) && screenController != null) {
                            screenController.dismissHint();
                        }
                    }

                    @Override
                    public void onRefresh() {
                        if (!isCurrentView(generation) || screenController == null)
                            return;
                        // loadTimeline() clears the indicator once the rebuilt
                        // rows arrive, as the SwipeRefreshLayout used to.
                        screenController.setRefreshing(true);
                        refreshAll();
                    }
                });
        lifecycleExecutor = Executors.newSingleThreadExecutor();
    }

    @Override
    public void onResume() {
        super.onResume();
        DatabaseHelper.getInstance(requireContext()).addAccessChangedListener(accessListener);
        refreshAll();
        refreshHandler.removeCallbacks(periodicRunnable);
        refreshHandler.postDelayed(periodicRunnable, PERIODIC_REFRESH_MS);
    }

    @Override
    public void onPause() {
        super.onPause();
        DatabaseHelper.getInstance(requireContext()).removeAccessChangedListener(accessListener);
        refreshHandler.removeCallbacks(refreshRunnable);
        refreshHandler.removeCallbacks(periodicRunnable);
    }

    @Override
    public void onDestroyView() {
        refreshHandler.removeCallbacks(refreshRunnable);
        refreshHandler.removeCallbacks(periodicRunnable);
        if (screenController != null) {
            screenController.invalidate();
            screenController = null;
        }
        if (lifecycleExecutor != null) {
            lifecycleExecutor.shutdownNow();
            lifecycleExecutor = null;
        }
        insightsData = null;
        composeView = null;
        viewGeneration++;
        super.onDestroyView();
    }

    private boolean isCurrentView(int generation) {
        return generation == viewGeneration
                && isAdded()
                && getView() != null
                && screenController != null;
    }

    private void openEntry(int uid, String appName, String packageName) {
        if (packageName == null || !isAdded())
            return;

        Intent intent = new Intent(requireContext(), DetailsActivity.class);
        intent.putExtra(DetailsActivity.INTENT_EXTRA_APP_NAME, appName);
        intent.putExtra(DetailsActivity.INTENT_EXTRA_APP_PACKAGENAME, packageName);
        intent.putExtra(DetailsActivity.INTENT_EXTRA_APP_UID, uid);
        startActivity(intent);
    }

    private void refreshAll() {
        if (screenController == null || getView() == null)
            return;
        int generation = viewGeneration;
        loadTimeline(generation);
        loadInsights(generation);
    }

    private void loadTimeline(final int generation) {
        if (!isCurrentView(generation))
            return;
        final int requestId = ++timelineRequestId;
        final Context context = requireContext().getApplicationContext();
        new AsyncTask<Void, Void, List<TimelineEntry>>() {
            @Override
            protected List<TimelineEntry> doInBackground(Void... voids) {
                return buildTimeline(context);
            }

            @Override
            protected void onPostExecute(List<TimelineEntry> entries) {
                if (!isCurrentView(generation) || screenController == null)
                    return;
                if (requestId != timelineRequestId)
                    return; // superseded by a request started after this one

                SharedPreferences prefs = PreferenceManager
                        .getDefaultSharedPreferences(requireContext());
                boolean trackerControlEnabled = prefs.getBoolean("enabled", false);
                boolean trackerRecordingEnabled = prefs.getBoolean("log_app", true);
                boolean trackerRecordingAvailable = !Util.isPlayStoreInstall(requireContext());
                TimelineEmptyAdapter.EmptyState emptyState = TimelineEmptyAdapter.stateFor(
                        trackerControlEnabled,
                        trackerRecordingEnabled,
                        trackerRecordingAvailable);
                TimelineEmptyState composeEmptyState = TimelineEmptyState.valueOf(emptyState.name());
                boolean showHint = TimelineHintAdapter.shouldShow(
                        !entries.isEmpty(),
                        prefs.getBoolean("hint_timeline_tap_entry", true));
                screenController.updateTimeline(
                        entries,
                        requireContext(),
                        composeEmptyState,
                        showHint);
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void loadInsights(final int generation) {
        final ExecutorService executor = lifecycleExecutor;
        if (executor == null || executor.isShutdown() || !isCurrentView(generation))
            return;
        final int requestId = ++insightsRequestId;
        final Context context = requireContext().getApplicationContext();
        executor.execute(() -> {
            InsightsData data = new InsightsDataProvider(context).computeInsights();
            refreshHandler.post(() -> {
                if (!isCurrentView(generation) || screenController == null)
                    return;
                if (requestId != insightsRequestId)
                    return; // superseded by a request started after this one
                insightsData = data;
                screenController.updateInsights(data);
            });
        });
    }

    private void shareInsights(final int generation) {
        final InsightsData data = insightsData;
        final ExecutorService executor = lifecycleExecutor;
        if (data == null || executor == null || executor.isShutdown() || !isCurrentView(generation))
            return;
        final Context context = requireContext().getApplicationContext();
        executor.execute(() -> {
            try {
                File imageFile = generateShareImage(context, data);
                if (imageFile == null)
                    return;
                refreshHandler.post(() -> {
                    if (!isCurrentView(generation))
                        return;
                    try {
                        android.net.Uri uri = FileProvider.getUriForFile(
                                context,
                                context.getPackageName() + ".provider",
                                imageFile);
                        String shareText = context.getString(
                                R.string.insights_share_message,
                                data.getBlockedTrackingAttempts(),
                                data.getUniqueTrackerCompanies());
                        Intent intent = new Intent(Intent.ACTION_SEND);
                        intent.setType("image/png");
                        intent.putExtra(Intent.EXTRA_STREAM, uri);
                        intent.putExtra(Intent.EXTRA_TEXT, shareText);
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(Intent.createChooser(intent,
                                context.getString(R.string.insights_share))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Exception e) {
                        android.util.Log.e(TAG, "Failed to share", e);
                        Toast.makeText(context, R.string.export_failed, Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                android.util.Log.e(TAG, "Failed to generate share image", e);
            }
        });
    }

    private File generateShareImage(Context context, InsightsData data) {
        try {
            LayoutInflater inflater = LayoutInflater.from(context);
            View shareView = inflater.inflate(R.layout.layout_insights_share, null);

            TextView tvTotalBlocked = shareView.findViewById(R.id.tvShareTotalBlocked);
            LinearLayout llBlockedStat = shareView.findViewById(R.id.llShareBlockedStat);
            TextView tvBlockedCount = shareView.findViewById(R.id.tvShareBlockedCount);
            TextView tvCompanies = shareView.findViewById(R.id.tvShareCompanies);
            LinearLayout llTopCompanies = shareView.findViewById(R.id.llShareTopCompanies);

            NumberFormat nf = NumberFormat.getNumberInstance(java.util.Locale.getDefault());
            tvTotalBlocked.setText(nf.format(data.getTotalTrackingAttempts()));
            llBlockedStat.setVisibility(View.VISIBLE);
            tvBlockedCount.setText(nf.format(data.getBlockedTrackingAttempts()));
            tvCompanies.setText(String.valueOf(data.getUniqueTrackerCompanies()));

            List<Pair<String, Integer>> top3 = data.getPervasiveTrackers().subList(
                    0, Math.min(data.getPervasiveTrackers().size(), 3));
            float density = context.getResources().getDisplayMetrics().density;
            for (Pair<String, Integer> company : top3) {
                LinearLayout row = new LinearLayout(context);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                rowParams.topMargin = (int) (4 * density);
                row.setLayoutParams(rowParams);
                row.setOrientation(LinearLayout.HORIZONTAL);

                TextView nameView = new TextView(context);
                nameView.setLayoutParams(new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                nameView.setText(company.first);
                nameView.setTextColor(Color.WHITE);
                nameView.setTextSize(12f);

                TextView countView = new TextView(context);
                countView.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
                countView.setText(context.getString(R.string.insights_in_apps, company.second));
                countView.setTextColor(Color.WHITE);
                countView.setTextSize(12f);
                countView.setTypeface(null, Typeface.BOLD);

                row.addView(nameView);
                row.addView(countView);
                llTopCompanies.addView(row);
            }

            int widthPx = (int) TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    400f,
                    context.getResources().getDisplayMetrics());
            int widthSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY);
            int heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
            shareView.measure(widthSpec, heightSpec);
            shareView.layout(0, 0, shareView.getMeasuredWidth(), shareView.getMeasuredHeight());

            Bitmap bitmap = Bitmap.createBitmap(
                    shareView.getMeasuredWidth(),
                    shareView.getMeasuredHeight(),
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            shareView.draw(canvas);

            File shareDir = new File(context.getCacheDir(), "share");
            if (!shareDir.exists() && !shareDir.mkdirs() && !shareDir.isDirectory())
                return null;
            File imageFile = new File(shareDir, "trackercontrol_insights.png");
            try (FileOutputStream out = new FileOutputStream(imageFile)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            bitmap.recycle();
            return imageFile;
        } catch (Exception e) {
            android.util.Log.e(TAG, "Failed to generate share image", e);
            return null;
        }
    }

    private List<TimelineEntry> buildTimeline(Context context) {
        DatabaseHelper dh = DatabaseHelper.getInstance(context);
        PackageManager pm = context.getPackageManager();
        // findTracker() reads from a static map populated lazily by
        // TrackerList.getInstance(). Without this call, opening the
        // app on the Timeline tab races with insights initialization
        // and every entry is silently dropped — appearing as an empty
        // "Watching for trackers…" screen even when there is data.
        TrackerList.getInstance(context);

        Map<Integer, Map<String, TrackerContact>> uidTrackers = new LinkedHashMap<>();
        Map<Integer, Long> uidLatestTime = new LinkedHashMap<>();
        Map<Integer, String[]> uidAppInfo = new LinkedHashMap<>();

        try (Cursor cursor = dh.getRecentTrackerActivity()) {
            if (cursor == null)
                return Collections.emptyList();

            int colUid = cursor.getColumnIndexOrThrow("uid");
            int colDaddr = cursor.getColumnIndexOrThrow("daddr");
            int colAllowed = cursor.getColumnIndexOrThrow("allowed");
            int colLastTime = cursor.getColumnIndexOrThrow("last_time");

            while (cursor.moveToNext()) {
                int uid = cursor.getInt(colUid);
                String daddr = cursor.getString(colDaddr);
                int allowed = cursor.getInt(colAllowed);
                long lastTime = cursor.getLong(colLastTime);

                Tracker tracker = TrackerList.findTracker(daddr);
                if (tracker == null)
                    continue;

                String companyName = tracker.getName();
                if (companyName == null)
                    continue;

                boolean blocked = allowed == 0;
                String category = tracker.getCategory();
                Map<String, TrackerContact> companyMap = uidTrackers.get(uid);
                if (companyMap == null) {
                    companyMap = new LinkedHashMap<>();
                    uidTrackers.put(uid, companyMap);
                }

                String key = companyName + "|" + blocked;
                TrackerContact existing = companyMap.get(key);
                if (existing == null || lastTime > existing.lastTime) {
                    companyMap.put(key, new TrackerContact(companyName, category, blocked, lastTime));
                }

                Long currentLatest = uidLatestTime.get(uid);
                if (currentLatest == null || lastTime > currentLatest)
                    uidLatestTime.put(uid, lastTime);

                if (!uidAppInfo.containsKey(uid)) {
                    // Keep activity for UIDs that belong to another profile,
                    // a clone, private space, or an app uninstalled since contact.
                    String appName = context.getString(R.string.unidentified_app_uid, uid);
                    String packageName = null;
                    String[] packages = Util.getPackagesForUid(pm, uid);
                    if (packages != null && packages.length > 0) {
                        packageName = packages[0];
                        appName = packageName;
                        try {
                            ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
                            appName = pm.getApplicationLabel(ai).toString();
                        } catch (PackageManager.NameNotFoundException ignored) {
                        }
                    }
                    uidAppInfo.put(uid, new String[] { appName, packageName });
                }
            }
        }

        List<TimelineEntry> entries = new ArrayList<>();
        for (Map.Entry<Integer, Map<String, TrackerContact>> e : uidTrackers.entrySet()) {
            int uid = e.getKey();
            String[] appInfo = uidAppInfo.get(uid);
            if (appInfo == null)
                continue;

            List<TrackerContact> trackers = new ArrayList<>(e.getValue().values());
            trackers.sort((a, b) -> {
                if (a.blocked != b.blocked)
                    return a.blocked ? -1 : 1;
                return Long.compare(b.lastTime, a.lastTime);
            });

            Long latestTime = uidLatestTime.get(uid);
            entries.add(new TimelineEntry(uid, appInfo[0], appInfo[1],
                    latestTime != null ? latestTime : 0, trackers));
        }

        entries.sort((a, b) -> Long.compare(b.mostRecentTime, a.mostRecentTime));
        return entries;
    }
}
