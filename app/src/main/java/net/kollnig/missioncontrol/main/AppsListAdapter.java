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

package net.kollnig.missioncontrol.main;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;

import net.kollnig.missioncontrol.BuildConfig;
import net.kollnig.missioncontrol.Common;
import net.kollnig.missioncontrol.DetailsActivity;
import net.kollnig.missioncontrol.R;
import net.kollnig.missioncontrol.data.App;

import java.util.ArrayList;
import java.util.List;

import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Provide views to RecyclerView with the directory entries.
 */
public class AppsListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
	public static final String INTENT_EXTRA_APP_ID = "INTENT_APP_ID";
	public static final String INTENT_EXTRA_APP_NAME = "INTENT_APP_NAME";
	private static final int TYPE_HEADER = 0;
	private static final int TYPE_ITEM = 1;
	public static int openedApp = -1;
	private Switch switchBlockAll;
	private List<App> mAppList = new ArrayList<>();
	private Fragment mFragment;

	private List<String> emailApps = new ArrayList<>();
	private boolean emailsEnabled;

	public AppsListAdapter (Fragment fragment) {
		this.mFragment = fragment;
		Context c = fragment.getContext();

		if (c != null) {
			this.emailApps = Common.getEmailApps(c);
			SharedPreferences settingsPref =
					PreferenceManager.getDefaultSharedPreferences(c);
			emailsEnabled = settingsPref.getBoolean
					(SettingsActivity.KEY_PREF_EMAIL_SWITCH, false);
		}
	}

	@Override
	public RecyclerView.ViewHolder onCreateViewHolder (ViewGroup viewGroup, int viewType) {
		View v;

		if (viewType == TYPE_ITEM) {
			v = LayoutInflater.from(viewGroup.getContext())
					.inflate(R.layout.list_apps_item, viewGroup, false);
			return new VHItem(v);
		} else if (viewType == TYPE_HEADER) {
			v = LayoutInflater.from(viewGroup.getContext())
					.inflate(R.layout.list_apps_header, viewGroup, false);
			switchBlockAll = v.findViewById(R.id.block_all_apps);
			return new VHHeader(v);
		}

		throw new RuntimeException("there is no type that matches the type " + viewType + " + make sure your using types correctly");
	}

	@Override
	public int getItemViewType (int position) {
		if (isPositionHeader(position))
			return TYPE_HEADER;

		return TYPE_ITEM;
	}

	private boolean isPositionHeader (int position) {
		return position == 0;
	}

	private App getItem (int position) {
		return mAppList.get(position - 1);
	}

	@Override
	public void onBindViewHolder (final RecyclerView.ViewHolder h, final int pos) {
		final AppBlocklistController w = AppBlocklistController.getInstance(mFragment.getContext());

		if (h instanceof VHItem) {
			final App app = getItem(pos);

			final VHItem holder = (VHItem) h;

			if (app.systemApp) {
				holder.getAppName().setText(app.name + " (System)");
			} else {
				holder.getAppName().setText(app.name);
			}
			holder.getAppDetails().setText(mFragment.getResources().getQuantityString(
					R.plurals.n_trackers_found, app.trackerCount, app.trackerCount));
			holder.getAppIcon().setImageDrawable(app.icon);

			if (!emailsEnabled && emailApps.contains(app.id)) {
				holder.itemView.setEnabled(false);
				holder.getSwitch().setEnabled(false);
				holder.getAppDetails().setText("Monitoring disabled");
			} else {
				holder.itemView.setEnabled(true);
				holder.getSwitch().setEnabled(true);
			}

			holder.itemView.setOnClickListener(view -> {
				Intent intent = new Intent(mFragment.getContext(), DetailsActivity.class);
				intent.putExtra(INTENT_EXTRA_APP_ID, app.id);
				intent.putExtra(INTENT_EXTRA_APP_NAME, app.name);
				mFragment.startActivity(intent);
			});

			if (BuildConfig.FLAVOR.equals("play")) {
				holder.getSwitch().setVisibility(View.GONE);
				return;
			}

			holder.getSwitch().setChecked(w.blockedApp(app.id));
			holder.getSwitch().setOnCheckedChangeListener((buttonView, isChecked) -> {
				if (!buttonView.isPressed()) return;

				if (isChecked) {
					w.addToBlocklist(app.id);
				} else {
					w.removeFromBlocklist(app.id);
				}

				switchBlockAll.setChecked(w.getBlockedCount() == mAppList.size());
			});
		} else {
			final VHHeader holder = (VHHeader) h;

			// Hide blocking functionality; make sure the hidden item has 0 height and width
			if (BuildConfig.FLAVOR.equals("play")) {
				holder.itemView.setVisibility(View.GONE);
				holder.itemView.setLayoutParams(new RecyclerView.LayoutParams(0, 0));
				return;
			}

			holder.getSwitch().setChecked(w.getBlockedCount() == mAppList.size());
			holder.getSwitch().setOnCheckedChangeListener((buttonView, isChecked) -> {
				if (!buttonView.isPressed()) return;

				if (isChecked) {
					BlockingConfirmDialog bd =
							new BlockingConfirmDialog(mFragment.getContext(), w, switchBlockAll) {
								@Override
								void blockAll () {
									for (App app : mAppList) {
										w.addToBlocklist(app.id);
									}
									notifyDataSetChanged();
								}
							};
					bd.confirmBlocking();
				} else {
					w.clear();
					notifyDataSetChanged();
				}
			});
		}
	}

	@Override
	public int getItemCount () {
		return mAppList.size() + 1;
	}

	public void setAppsList (List<App> apps) {
		mAppList = apps;
	}

	/**
	 * Provide a reference to the type of views that you are using (custom ViewHolder)
	 */
	public static class VHItem extends RecyclerView.ViewHolder {
		private final TextView mAppName;
		private final TextView mAppDetails;
		private final ImageView mAppIcon;
		private final Switch mSwitch;

		public VHItem (View v) {
			super(v);
			mAppName = v.findViewById(R.id.app_name);
			mAppDetails = v.findViewById(R.id.app_details);
			mAppIcon = v.findViewById(R.id.app_icon);
			mSwitch = v.findViewById(R.id.block_app);
		}

		public TextView getAppDetails () {
			return mAppDetails;
		}

		public TextView getAppName () {
			return mAppName;
		}

		public ImageView getAppIcon () {
			return mAppIcon;
		}

		public Switch getSwitch () {
			return mSwitch;
		}
	}

	class VHHeader extends RecyclerView.ViewHolder {
		private final Switch mSwitch;

		public VHHeader (View v) {
			super(v);

			mSwitch = v.findViewById(R.id.block_all_apps);
		}

		public Switch getSwitch () {
			return mSwitch;
		}
	}
}