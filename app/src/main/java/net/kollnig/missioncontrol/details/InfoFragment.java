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
import android.os.Bundle;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.RelativeSizeSpan;
import android.util.ArrayMap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import com.github.mikephil.charting.charts.PieChart;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.PieData;
import com.github.mikephil.charting.data.PieDataSet;
import com.github.mikephil.charting.data.PieEntry;
import com.github.mikephil.charting.formatter.LargeValueFormatter;
import com.github.mikephil.charting.highlight.Highlight;
import com.github.mikephil.charting.listener.OnChartValueSelectedListener;
import com.github.mikephil.charting.utils.ColorTemplate;

import net.kollnig.missioncontrol.data.Database;
import net.kollnig.missioncontrol.data.Host;
import net.kollnig.missioncontrol.data.Tracker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import androidx.fragment.app.Fragment;
import eu.faircode.netguard.R;

/**
 * A simple {@link Fragment} subclass.
 * Use the {@link InfoFragment#newInstance} factory method to
 * create an instance of this fragment.
 */
public class InfoFragment extends Fragment {
	private static final String ARG_APP_ID = "app-id";
	private final String TAG = InfoFragment.class.getSimpleName();
	Map<String, DataItem> packetsPerCompany;

	public InfoFragment () {
		// Required empty public constructor
	}

	public static InfoFragment newInstance (String appId) {
		InfoFragment fragment = new InfoFragment();
		Bundle args = new Bundle();
		args.putString(ARG_APP_ID, appId);
		fragment.setArguments(args);
		return fragment;
	}

	@Override
	public View onCreateView (LayoutInflater inflater, ViewGroup container,
	                          Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_info, container, false);
	}

	@Override
	public void onViewCreated (View v, Bundle savedInstanceState) {
		super.onViewCreated(v, savedInstanceState);

		// Load arguments
		Bundle arguments = getArguments();
		String appId = arguments.getString(ARG_APP_ID);

		// Load data
		Context context = v.getContext();
		Database database = Database.getInstance(context);
		List<Tracker> details = database.getAppDetails(appId);

		int totalPackets = 0;
		packetsPerCompany = new ArrayMap<>();
		for (Tracker t : details) {
			String name = t.getRoot();
			if (name == null)
				name = getString(R.string.company_unknown);

			DataItem item = packetsPerCompany.get(name);
			if (item != null) {
				item.packetCount += t.packetCount;
				item.hosts.addAll(t.hosts);
			} else {
				packetsPerCompany.put(name, new DataItem(t.packetCount, t.hosts));
			}
			totalPackets += t.packetCount;
		}

		if (totalPackets > 0)
			loadChart(v);
	}

	private void loadChart (View v) {
		PieChart companyChart = v.findViewById(R.id.company_chart);
		List<PieEntry> yvalues = new ArrayList<>();
		for (Map.Entry<String, DataItem> entry : packetsPerCompany.entrySet()) {
			yvalues.add(
					new PieEntry(entry.getValue().packetCount, entry.getKey(), entry.getValue()));
		}
		setChart(companyChart, yvalues, "Data packets\nper company");
	}

	/**
	 * Collect many colours
	 *
	 * @return
	 */
	private List<Integer> getColours () {
		List<Integer> colors = new ArrayList<>();
		for (int c : ColorTemplate.MATERIAL_COLORS)
			colors.add(c);
		for (int c : ColorTemplate.VORDIPLOM_COLORS)
			colors.add(c);
		for (int c : ColorTemplate.JOYFUL_COLORS)
			colors.add(c);
		for (int c : ColorTemplate.COLORFUL_COLORS)
			colors.add(c);
		for (int c : ColorTemplate.LIBERTY_COLORS)
			colors.add(c);
		for (int c : ColorTemplate.PASTEL_COLORS)
			colors.add(c);
		colors.add(ColorTemplate.getHoloBlue());

		return colors;
	}

	private void setChart (PieChart chart, List<PieEntry> yvalues, String caption) {
		// Prepare data
		PieDataSet dataSet = new PieDataSet(yvalues, null);
		PieData data = new PieData(dataSet);
		data.setValueFormatter(new LargeValueFormatter());
		data.setValueTextSize(16f);
		dataSet.setColors(getColours());

		// Prepare caption
		SpannableString centerText = new SpannableString(caption);
		centerText.setSpan(new RelativeSizeSpan(1.5f), 0, centerText.length(), 0);

		// Set chart
		chart.getDescription().setEnabled(false);
		chart.setData(data);
		chart.setRotationEnabled(false);
		chart.setHighlightPerTapEnabled(true);
		chart.setHoleRadius(58f);
		chart.setTransparentCircleRadius(58f);
		//chart.setUsePercentValues(true);
		chart.setCenterText(centerText);


		chart.setOnChartValueSelectedListener(new OnChartValueSelectedListener() {
			@Override
			public void onNothingSelected () {

			}

			@Override
			public void onValueSelected (Entry e, Highlight h) {
				String message = "Found hosts:\n• "
						+ TextUtils.join("\n• ", ((DataItem) e.getData()).hosts);
				Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();
			}
		});

		chart.getLegend().setEnabled(false);
	}

	class DataItem {
		Integer packetCount;
		List<Host> hosts = new ArrayList<>();

		public DataItem (Integer packetCount, List<Host> hosts) {
			this.packetCount = packetCount;
			this.hosts = hosts;
		}
	}
}
