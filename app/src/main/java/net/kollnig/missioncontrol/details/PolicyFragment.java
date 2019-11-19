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

package net.kollnig.missioncontrol.details;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;

import net.dankito.readability4j.Article;
import net.dankito.readability4j.Readability4J;
import net.kollnig.missioncontrol.Common;
import net.kollnig.missioncontrol.DetailsActivity;
import net.kollnig.missioncontrol.R;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import static net.kollnig.missioncontrol.Common.fetch;

/**
 * A simple {@link Fragment} subclass.
 * Use the {@link PolicyFragment#newInstance} factory method to
 * create an instance of this fragment.
 */
public class PolicyFragment extends Fragment implements DetailsActivity.OnAppInfoLoadedListener {
	private static final String ARG_APP_ID = "app-id";
	private static final String POLICY_HTML = "policy-html";
	private final String TAG = PolicyFragment.class.getSimpleName();
	TextView txtPolicy;
	ProgressBar pbPolicy;
	String html;
	FloatingActionButton fab;
	String policyUrl = null;
	private String mAppId;

	public PolicyFragment () {
		// Required empty public constructor
	}

	public static PolicyFragment newInstance (String appId) {
		PolicyFragment fragment = new PolicyFragment();
		Bundle args = new Bundle();
		args.putString(ARG_APP_ID, appId);
		fragment.setArguments(args);
		return fragment;
	}

	@Override
	public View onCreateView (LayoutInflater inflater, ViewGroup container,
	                          Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_policy, container, false);
	}

	@Override
	public void onSaveInstanceState (final Bundle outState) {
		if (html != null)
			outState.putString(POLICY_HTML, html);
		super.onSaveInstanceState(outState);
	}

	@Override
	public void onCreate (Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Bundle bundle = getArguments();

		if (bundle != null) {
			mAppId = getArguments().getString(ARG_APP_ID);
		}
	}

	@Override
	public void onViewCreated (View v, Bundle savedInstanceState) {
		super.onViewCreated(v, savedInstanceState);

		txtPolicy = v.findViewById(R.id.txtPolicy);
		pbPolicy = v.findViewById(R.id.pbPolicy);

		fab = v.findViewById(R.id.fab);
		fab.setOnClickListener(v1 -> {
			if (policyUrl == null) {
				Snackbar.make(v1, getString(R.string.policy_loading_error), Snackbar.LENGTH_LONG)
						.show();
			} else {
				// Get URL
				Intent browserIntent = Common.browse(policyUrl);
				startActivity(browserIntent);
			}
		});
		fab.hide();

		((DetailsActivity) getActivity()).addListener(this);

		if (savedInstanceState != null)
			html = savedInstanceState.getString(POLICY_HTML);

		if (!DetailsActivity.contactGoogle)
			html = getString(R.string.no_external_servers);

		if (html != null) {
			displayHtml(html);
			loadingFinished();
		} else if (DetailsActivity.app != null) {
			appInfoLoaded();
		}
	}

	@Override
	public void onDetach () {
		super.onDetach();
		((DetailsActivity) getActivity()).removeListener(this);
	}

	@SuppressWarnings("deprecation")
	public void displayHtml (String html) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
			txtPolicy.setText(Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT));
		} else {
			txtPolicy.setText(Html.fromHtml(html));
		}
		txtPolicy.setMovementMethod(LinkMovementMethod.getInstance());
	}

	public void loadingFinished () {
		txtPolicy.setVisibility(View.VISIBLE);
		pbPolicy.setVisibility(View.GONE);
		fab.show();
	}

	@Override
	public void appInfoLoaded () {
		new Thread(new PolicyLoader()).start();
	}

	private class PolicyLoader implements Runnable {
		private void onError (final String url) {
			FragmentActivity a = getActivity();
			if (a == null) return;

			a.runOnUiThread(() -> {
				if (url != null) {
					html = getString(R.string.parse_error, Html.escapeHtml(url));
					displayHtml(html);
				} else {
					txtPolicy.setText(getString(R.string.policy_loading_error));
				}
				loadingFinished();
			});
		}

		@Override
		public void run () {
			policyUrl = DetailsActivity.app.policyUrl;
			if (policyUrl == null) {
				Log.d(TAG, "Fetching of policy url failed.");
				onError(null);
				return;
			}

			String privacyHtml;
			try {
				privacyHtml = fetch(policyUrl);
			} catch (Exception e) {
				Log.d(TAG, "Fetching of policy from url failed.");
				onError(policyUrl);
				return;
			}

			// Parse html with Mozilla's Readability.js (ported to Android)
			Readability4J readability4J = new Readability4J(policyUrl, privacyHtml);
			Article article = readability4J.parse();
			String title = article.getTitle();
			String content = article.getContent();
			if (content == null || title == null) {
				Log.d(TAG, "Parsing of policy failed.");
				onError(policyUrl);
				return;
			}

			html = "<h1>" + Html.escapeHtml(title) + "</h1>" + content;

			// Display in UI
			FragmentActivity a = getActivity();
			if (a == null) return;
			a.runOnUiThread(() -> {
				displayHtml(html);
				loadingFinished();
			});
		}
	}
}
