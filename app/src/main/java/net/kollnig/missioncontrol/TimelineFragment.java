package net.kollnig.missioncontrol;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.button.MaterialButton;

import net.kollnig.missioncontrol.data.TimelineEntry;
import net.kollnig.missioncontrol.data.Tracker;
import net.kollnig.missioncontrol.data.TrackerContact;
import net.kollnig.missioncontrol.data.TrackerList;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import eu.faircode.netguard.DatabaseHelper;

public class TimelineFragment extends Fragment implements TimelineAdapter.OnEntryClickListener {

    private static final long REFRESH_DEBOUNCE_MS = 500L;

    private TimelineAdapter adapter;
    private LinearLayout llEmpty;
    private TextView tvEmptyTitle;
    private TextView tvEmptySubtitle;
    private MaterialButton btnOpenApp;
    private RecyclerView rvTimeline;
    private SwipeRefreshLayout swipeRefresh;

    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshRunnable = this::loadTimeline;
    private final DatabaseHelper.AccessChangedListener accessListener =
            () -> {
                refreshHandler.removeCallbacks(refreshRunnable);
                refreshHandler.postDelayed(refreshRunnable, REFRESH_DEBOUNCE_MS);
            };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_timeline, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        llEmpty = view.findViewById(R.id.llEmpty);
        tvEmptyTitle = view.findViewById(R.id.tvEmptyTitle);
        tvEmptySubtitle = view.findViewById(R.id.tvEmptySubtitle);
        btnOpenApp = view.findViewById(R.id.btnOpenApp);
        rvTimeline = view.findViewById(R.id.rvTimeline);
        swipeRefresh = view.findViewById(R.id.swipeRefreshTimeline);

        rvTimeline.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new TimelineAdapter(requireContext(), this);
        rvTimeline.setAdapter(adapter);

        swipeRefresh.setOnRefreshListener(this::loadTimeline);

        btnOpenApp.setOnClickListener(v -> {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(home);
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        DatabaseHelper.getInstance(requireContext()).addAccessChangedListener(accessListener);
        loadTimeline();
    }

    @Override
    public void onPause() {
        super.onPause();
        DatabaseHelper.getInstance(requireContext()).removeAccessChangedListener(accessListener);
        refreshHandler.removeCallbacks(refreshRunnable);
    }

    @Override
    public void onEntryClick(TimelineEntry entry) {
        Intent intent = new Intent(requireContext(), DetailsActivity.class);
        intent.putExtra(DetailsActivity.INTENT_EXTRA_APP_NAME, entry.appName);
        intent.putExtra(DetailsActivity.INTENT_EXTRA_APP_PACKAGENAME, entry.packageName);
        intent.putExtra(DetailsActivity.INTENT_EXTRA_APP_UID, entry.uid);
        startActivity(intent);
    }

    private void loadTimeline() {
        new AsyncTask<Void, Void, List<TimelineEntry>>() {
            @Override
            protected List<TimelineEntry> doInBackground(Void... voids) {
                return buildTimeline();
            }

            @Override
            protected void onPostExecute(List<TimelineEntry> entries) {
                if (!isAdded())
                    return;
                adapter.setEntries(entries);
                swipeRefresh.setRefreshing(false);
                if (entries.isEmpty()) {
                    showEmptyState();
                } else {
                    llEmpty.setVisibility(View.GONE);
                    rvTimeline.setVisibility(View.VISIBLE);
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void showEmptyState() {
        rvTimeline.setVisibility(View.GONE);
        llEmpty.setVisibility(View.VISIBLE);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        boolean enabled = prefs.getBoolean("enabled", false);
        if (enabled) {
            tvEmptyTitle.setText(R.string.timeline_empty_enabled_title);
            tvEmptySubtitle.setText(R.string.timeline_empty_enabled_subtitle);
            btnOpenApp.setVisibility(View.VISIBLE);
        } else {
            tvEmptyTitle.setText(R.string.timeline_empty_disabled_title);
            tvEmptySubtitle.setText(R.string.timeline_empty_disabled_subtitle);
            btnOpenApp.setVisibility(View.GONE);
        }
    }

    private List<TimelineEntry> buildTimeline() {
        DatabaseHelper dh = DatabaseHelper.getInstance(requireContext());
        PackageManager pm = requireContext().getPackageManager();

        // uid -> (companyName -> TrackerContact with latest time and blocked state)
        // We use a compound key of "companyName|blocked" to keep separate entries
        // for the same company when it has both blocked and allowed connections.
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
                if (currentLatest == null || lastTime > currentLatest) {
                    uidLatestTime.put(uid, lastTime);
                }

                if (!uidAppInfo.containsKey(uid)) {
                    String appName = Integer.toString(uid);
                    String packageName = null;
                    String[] packages = pm.getPackagesForUid(uid);
                    if (packages != null && packages.length > 0) {
                        packageName = packages[0];
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
            if (appInfo == null || appInfo[1] == null)
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
