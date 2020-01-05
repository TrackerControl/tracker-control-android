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

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.tabs.TabLayout;

import net.kollnig.missioncontrol.Common;
import net.kollnig.missioncontrol.DetailsActivity;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import eu.faircode.netguard.R;
import eu.faircode.netguard.Util;

import static net.kollnig.missioncontrol.DetailsPagesAdapter.tabTrackersPosition;

/**
 * A simple {@link Fragment} subclass.
 * Use the {@link ActionsFragment#newInstance} factory method to
 * create an instance of this fragment.
 */
public class ActionsFragment extends Fragment implements View.OnClickListener {
	private static final String ARG_APP_ID = "app-id";
	private static final String ARG_APP_NAME = "app-name";
	private final String TAG = ActionsFragment.class.getSimpleName();
	private String appId;
	private String appName;

	public ActionsFragment () {
		// Required empty public constructor
	}

	public static ActionsFragment newInstance (String appId, String appName) {
		ActionsFragment fragment = new ActionsFragment();
		Bundle args = new Bundle();
		args.putString(ARG_APP_ID, appId);
		args.putString(ARG_APP_NAME, appName);
		fragment.setArguments(args);
		return fragment;
	}

	@Override
	public View onCreateView (LayoutInflater inflater, ViewGroup container,
	                          Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_actions, container, false);
	}

	@Override
	public void onCreate (Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Bundle bundle = getArguments();
	}

	@Override
	public void onViewCreated (final View v, Bundle savedInstanceState) {
		super.onViewCreated(v, savedInstanceState);

		// Load arguments
		Bundle arguments = getArguments();
		appId = arguments.getString(ARG_APP_ID);
		appName = arguments.getString(ARG_APP_NAME);

		// Set up buttons
		v.findViewById(R.id.btnTrackers).setOnClickListener(this);
		v.findViewById(R.id.btnAdSettings).setOnClickListener(this);
		v.findViewById(R.id.btnReqData).setOnClickListener(this);
		v.findViewById(R.id.btnReqDeletion).setOnClickListener(this);
		v.findViewById(R.id.btnContactDev).setOnClickListener(this);
		v.findViewById(R.id.btnContactGoogle).setOnClickListener(this);
		v.findViewById(R.id.btnContactOfficials).setOnClickListener(this);

		if (!Common.hasAdSettings(getContext()))
			v.findViewById(R.id.adsettings_card).setVisibility(View.GONE);

		if (Util.isPlayStoreInstall(getContext())) {
			v.findViewById(R.id.tracker_card).setVisibility(View.GONE);
		}
	}

	@Override
	public void onClick (View v) {
		int id = v.getId();
		if (id == R.id.btnTrackers) {
			TabLayout tabs = getActivity().findViewById(R.id.tabs);
			tabs.getTabAt(tabTrackersPosition).select();
		} else if (id == R.id.btnAdSettings) {
			if (Common.hasAdSettings(getContext())) {
				startActivity(Common.adSettings());
			} else {
				Snackbar.make(getView(), R.string.play_services_required, Snackbar.LENGTH_LONG).show();
			}
		} else if (id == R.id.btnReqData || id == R.id.btnReqDeletion || id == R.id.btnContactDev) {
			String mail = null;

			if (DetailsActivity.app != null && DetailsActivity.app.developerMail != null)
				mail = DetailsActivity.app.developerMail;

			String subject = null, body = null;
			if (v.getId() == R.id.btnReqData) {
				subject = getString(R.string.subject_request_data);
				body = getString(R.string.body_request_data, appName, appId);
			}

			if (v.getId() == R.id.btnReqDeletion) {
				subject = getString(R.string.subject_request_data);
				body = getString(R.string.body_request_data, appName, appId);
			}

			sendEmail(mail, subject, body);
		} else if (id == R.id.btnContactGoogle) {
			sendEmail(getString(R.string.google_dpo_mail), null, null);
		} else if (id == R.id.btnContactOfficials) {
			Intent browserIntent = Common.browse(getString(R.string.dpas_overview_url));
			startActivity(browserIntent);
		}
	}

	private void sendEmail (@Nullable String email, String subject, String body) {
		Log.d(TAG, "Email: " + email + " Subject: " + subject);
		Intent i = new Intent(Intent.ACTION_SEND);
		i.setType("message/rfc822");
		if (email != null) {
			i.putExtra(Intent.EXTRA_EMAIL, new String[]{email});
		}
		i.putExtra(Intent.EXTRA_SUBJECT, subject);
		i.putExtra(Intent.EXTRA_TEXT, body);
		try {
			startActivity(Intent.createChooser(i, "Send email via..."));
		} catch (android.content.ActivityNotFoundException ex) {
			Snackbar.make(getView(), R.string.no_mail_service, Snackbar.LENGTH_LONG).show();
		}
	}
}
