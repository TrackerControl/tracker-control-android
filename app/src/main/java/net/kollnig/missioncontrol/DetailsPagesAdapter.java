/*
 * Copyright (C) 2019 Konrad Kollnig, University of Oxford
 *
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * TrackerControl is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with TrackerControl. If not, see <http://www.gnu.org/licenses/>.
 */

package net.kollnig.missioncontrol;

import android.content.Context;
import android.support.annotation.Nullable;
import android.support.annotation.StringRes;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentPagerAdapter;

import net.kollnig.missioncontrol.details.ActionsFragment;
import net.kollnig.missioncontrol.details.InfoFragment;
import net.kollnig.missioncontrol.details.PolicyFragment;
import net.kollnig.missioncontrol.details.TransmissionsFragment;

/**
 * A [FragmentPagerAdapter] that returns a fragment corresponding to
 * one of the sections/tabs/pages.
 */
public class DetailsPagesAdapter extends FragmentPagerAdapter {
	@StringRes
	private static final int[] TAB_TITLES = new int[]{
			R.string.tab_actions,
			R.string.tab_transmissions,
			R.string.tab_policy,
			R.string.tab_info,
	};
	public static int tabTransmissionsPosition = 1;

	private final String TAG = DetailsPagesAdapter.class.getSimpleName();
	private final Context mContext;

	private Fragment fInfo;
	private Fragment fTransmission;
	private Fragment fActions;
	private Fragment fPolicy;

	public DetailsPagesAdapter (final Context context, FragmentManager fm, String appId, String appName) {
		super(fm);

		mContext = context;

		fInfo = InfoFragment.newInstance(appId);
		fTransmission = TransmissionsFragment.newInstance(appId);
		fPolicy = PolicyFragment.newInstance(appId);
		fActions = ActionsFragment.newInstance(appId, appName);
	}

	@Override
	public Fragment getItem (int position) {
		switch (position) {
			case 0:
				return fActions;
			case 1:
				return fTransmission;
			case 2:
				return fPolicy;
			case 3:
				return fInfo;
		}
		return null;
	}

	@Nullable
	@Override
	public CharSequence getPageTitle (int position) {
		return mContext.getResources().getString(TAB_TITLES[position]);
	}

	@Override
	public int getCount () {
		return TAB_TITLES.length;
	}
}