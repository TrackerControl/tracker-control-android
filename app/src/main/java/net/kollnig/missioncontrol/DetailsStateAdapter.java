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

package net.kollnig.missioncontrol;

import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import net.kollnig.missioncontrol.details.ActionsFragment;
import net.kollnig.missioncontrol.details.CountriesFragment;
import net.kollnig.missioncontrol.details.TrackersFragment;


/**
 * A [FragmentStateAdapter] that returns a fragment corresponding to
 * one of the sections/tabs/pages.
 */
public class DetailsStateAdapter extends FragmentStateAdapter {
    @StringRes
    private static int[] TAB_TITLES = {
            R.string.tab_trackers,
            R.string.tab_actions,
            R.string.tab_countries,
    };
    private final String TAG = DetailsStateAdapter.class.getSimpleName();
    private final int mUid;

    private final TrackersFragment fTrackers;
    private final ActionsFragment fActions;
    private CountriesFragment fCountries;

    public DetailsStateAdapter(FragmentActivity fa, String appId, String appName, int uid) {
        super(fa);

        mUid = uid;

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            TAB_TITLES = new int[]{
                    R.string.tab_trackers,
                    R.string.tab_actions,
            };
        } else {
            TAB_TITLES = new int[]{
                    R.string.tab_trackers,
                    R.string.tab_actions,
                    R.string.tab_countries,
            };
        }

        fTrackers = TrackersFragment.newInstance(appId, uid);
        fActions = ActionsFragment.newInstance(appId, appName);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            switch (position) {
                case 0:
                    return fTrackers;
                case 1:
                    return fActions;
            }
        } else {
            switch (position) {
                case 0:
                    return fTrackers;
                case 1:
                    return fActions;
                case 2:
                    if (fCountries == null)
                        fCountries = CountriesFragment.newInstance(mUid);
                    return fCountries;
            }
        }

        return fTrackers;
    }

    public int getPageTitle(final int position) {
        return TAB_TITLES[position];
    }

    @Override
    public int getItemCount() {
        return TAB_TITLES.length;
    }


    void updateTrackerLists() {
        if (fTrackers != null)
            fTrackers.updateTrackerList();
    }
}