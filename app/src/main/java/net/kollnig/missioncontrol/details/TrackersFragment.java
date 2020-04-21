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
 * along with TrackerControl.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2019 Konrad Kollnig, University of Oxford
 */

package net.kollnig.missioncontrol.details;

import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import net.kollnig.missioncontrol.R;
import net.kollnig.missioncontrol.data.TrackerCategory;
import net.kollnig.missioncontrol.data.TrackerList;

import java.util.List;


/**
 * A fragment representing a list of Items.
 */
public class TrackersFragment extends Fragment {
    private static final String ARG_APP_ID = "app-id";
    private static final String ARG_APP_UID = "app-uid";
    private final String TAG = TrackersFragment.class.getSimpleName();
    private TrackerList trackerList;
    private String mAppId;
    private int mAppUid;

    private SwipeRefreshLayout swipeRefresh;
    private TrackersListAdapter adapter;

    private RecyclerView recyclerView;
    private View emptyView;
    private Button btnLaunch;

    private boolean running = false;

    /**
     * Mandatory empty constructor for the fragment manager to instantiate the
     * fragment (e.g. upon screen orientation changes).
     */
    public TrackersFragment() {
    }

    public static TrackersFragment newInstance(String appId, int uid) {
        TrackersFragment fragment = new TrackersFragment();
        Bundle args = new Bundle();
        args.putString(ARG_APP_ID, appId);
        args.putInt(ARG_APP_UID, uid);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle bundle = getArguments();
        mAppId = bundle.getString(ARG_APP_ID);
        mAppUid = bundle.getInt(ARG_APP_UID);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_trackers, container, false);

        running = true;

        Context context = v.getContext();
        trackerList = TrackerList.getInstance(context);
        recyclerView = v.findViewById(R.id.transmissions_list);
        recyclerView.setLayoutManager(new LinearLayoutManager(context));
        adapter = new TrackersListAdapter(getContext(), recyclerView, mAppId);
        recyclerView.setAdapter(adapter);

        swipeRefresh = v.findViewById(R.id.swipeRefresh);
        swipeRefresh.setOnRefreshListener(this::updateTrackerList);

        emptyView = v.findViewById(R.id.empty);
        btnLaunch = v.findViewById(R.id.btnLaunch);
        Context c = getContext();
        if (c != null) {
            Intent intent = c.getPackageManager().getLaunchIntentForPackage(mAppId);

            final Intent launch = (intent == null ||
                    intent.resolveActivity(c.getPackageManager()) == null ? null : intent);

            if (launch == null) {
                btnLaunch.setVisibility(View.GONE);
            } else {
                btnLaunch.setOnClickListener(view -> c.startActivity(launch));
            }
        }

        return v;
    }

    @Override
    public void onResume() {
        super.onResume();

        updateTrackerList();
    }

    private void updateTrackerList() {
        new AsyncTask<Object, Object, List<TrackerCategory>>() {
            private boolean refreshing = true;

            @Override
            protected void onPreExecute() {
                swipeRefresh.post(() -> {
                    if (refreshing)
                        swipeRefresh.setRefreshing(true);
                });
            }

            @Override
            protected List<TrackerCategory> doInBackground(Object... arg) {
                return trackerList.getAppTrackers(mAppUid);
            }

            @Override
            protected void onPostExecute(List<TrackerCategory> result) {
                if (running) {
                    if (adapter != null) {
                        adapter.set(result);

                        if (result.size() == 0) {
                            emptyView.setVisibility(View.VISIBLE);
                            recyclerView.setVisibility(View.GONE);
                        } else {
                            emptyView.setVisibility(View.GONE);
                            recyclerView.setVisibility(View.VISIBLE);
                        }
                    }

                    if (swipeRefresh != null) {
                        refreshing = false;
                        swipeRefresh.setRefreshing(false);
                    }
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        running = false;
    }
}
