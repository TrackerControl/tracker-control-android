package net.kollnig.missioncontrol;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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

public class ActivityTimeline extends AppCompatActivity implements TimelineAdapter.OnEntryClickListener {

    private TimelineAdapter adapter;
    private TextView tvEmpty;
    private RecyclerView rvTimeline;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_timeline);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null)
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        // Pad the AppBarLayout so its colored background extends behind the status bar
        com.google.android.material.appbar.AppBarLayout appBar = findViewById(R.id.appbar);
        final int appBarInitialTop = appBar.getPaddingTop();
        ViewCompat.setOnApplyWindowInsetsListener(appBar, (v, insets) -> {
            Insets sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), appBarInitialTop + sysBars.top,
                    v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });

        tvEmpty = findViewById(R.id.tvEmpty);
        rvTimeline = findViewById(R.id.rvTimeline);
        rvTimeline.setLayoutManager(new LinearLayoutManager(this));

        adapter = new TimelineAdapter(this, this);
        rvTimeline.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadTimeline();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    public void onEntryClick(TimelineEntry entry) {
        Intent intent = new Intent(this, DetailsActivity.class);
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
                adapter.setEntries(entries);
                tvEmpty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
                rvTimeline.setVisibility(entries.isEmpty() ? View.GONE : View.VISIBLE);
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private List<TimelineEntry> buildTimeline() {
        DatabaseHelper dh = DatabaseHelper.getInstance(this);
        PackageManager pm = getPackageManager();

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

                // Resolve to tracker — skip non-tracker destinations
                Tracker tracker = TrackerList.findTracker(daddr);
                if (tracker == null)
                    continue;

                String companyName = tracker.getName();
                if (companyName == null)
                    continue;

                boolean blocked = allowed == 0;
                String category = tracker.getCategory();

                // Track per-uid
                Map<String, TrackerContact> companyMap = uidTrackers.get(uid);
                if (companyMap == null) {
                    companyMap = new LinkedHashMap<>();
                    uidTrackers.put(uid, companyMap);
                }

                // Key by company+blocked to keep blocked/allowed separate for same company
                String key = companyName + "|" + blocked;
                TrackerContact existing = companyMap.get(key);
                if (existing == null || lastTime > existing.lastTime) {
                    companyMap.put(key, new TrackerContact(companyName, category, blocked, lastTime));
                }

                // Track latest time per uid
                Long currentLatest = uidLatestTime.get(uid);
                if (currentLatest == null || lastTime > currentLatest) {
                    uidLatestTime.put(uid, lastTime);
                }

                // Resolve app info once per uid
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

        // Build entries
        List<TimelineEntry> entries = new ArrayList<>();
        for (Map.Entry<Integer, Map<String, TrackerContact>> e : uidTrackers.entrySet()) {
            int uid = e.getKey();
            String[] appInfo = uidAppInfo.get(uid);
            if (appInfo == null || appInfo[1] == null)
                continue;

            List<TrackerContact> trackers = new ArrayList<>(e.getValue().values());
            // Sort: blocked first, then by most recent
            trackers.sort((a, b) -> {
                if (a.blocked != b.blocked)
                    return a.blocked ? -1 : 1;
                return Long.compare(b.lastTime, a.lastTime);
            });

            Long latestTime = uidLatestTime.get(uid);
            entries.add(new TimelineEntry(uid, appInfo[0], appInfo[1],
                    latestTime != null ? latestTime : 0, trackers));
        }

        // Sort by most recent first
        entries.sort((a, b) -> Long.compare(b.mostRecentTime, a.mostRecentTime));
        return entries;
    }
}
