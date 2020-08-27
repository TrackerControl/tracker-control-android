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

import android.content.Context;
import android.os.Build;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;

import net.kollnig.missioncontrol.details.ActionsFragment;
import net.kollnig.missioncontrol.details.CountriesFragment;
import net.kollnig.missioncontrol.details.TrackersFragment;


/**
 * A [FragmentPagerAdapter] that returns a fragment corresponding to
 * one of the sections/tabs/pages.
 */
public class DetailsPagesAdapter extends FragmentPagerAdapter {
    public static int tabTrackersPosition = 0;
    @StringRes
    private static int[] TAB_TITLES = new int[]{
            R.string.tab_trackers,
            R.string.tab_actions,
            R.string.tab_countries,
    };
    private final String TAG = DetailsPagesAdapter.class.getSimpleName();
    private final Context mContext;
    private int mUid;

    private Fragment fTrackers;
    private Fragment fActions;
    private Fragment fCountries;

    public DetailsPagesAdapter(final Context context, FragmentManager fm, String appId, String appName, int uid) {
        super(fm);

        mContext = context;
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

    @Override
    public Fragment getItem(int position) {
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

        return null;
    }

    @Nullable
    @Override
    public CharSequence getPageTitle(int position) {
        return mContext.getResources().getString(TAB_TITLES[position]);
    }

    @Override
    public int getCount() {
        return TAB_TITLES.length;
    }
}