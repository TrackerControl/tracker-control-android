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

package net.kollnig.missioncontrol.details;

import android.content.Context;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;

import androidx.collection.ArrayMap;
import androidx.fragment.app.Fragment;

import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.exception.GeoIp2Exception;
import com.maxmind.geoip2.model.CountryResponse;
import com.maxmind.geoip2.record.Country;

import net.kollnig.missioncontrol.R;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.util.Map;

import eu.faircode.netguard.DatabaseHelper;
import jp.gr.java_conf.androtaku.geomap.GeoMapView;

import static net.kollnig.missioncontrol.data.TrackerList.findTracker;

public class CountriesFragment extends Fragment {
    private static final String ARG_APP_UID = "app-uid";
    private final String TAG = CountriesFragment.class.getSimpleName();
    private int mAppUid;

    public CountriesFragment() {
        // Required empty public constructor
    }

    public static CountriesFragment newInstance(int uid) {
        CountriesFragment fragment = new CountriesFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_APP_UID, uid);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_countries, container, false);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle bundle = getArguments();
    }

    /**
     * Retrieves information about all seen trackers
     *
     * @return A list of seen trackers
     */
    public synchronized Map<String, Integer> getHostCountriesCount(int uid) {
        Map<String, Integer> countryToCount = new ArrayMap<>();

        try {
            Context context = getContext();
            if (context == null)
                return countryToCount;

            InputStream database = context.getAssets().open("GeoLite2-Country.mmdb");
            DatabaseReader reader = new DatabaseReader.Builder(database).build();

            DatabaseHelper dh = DatabaseHelper.getInstance(getContext());
            Cursor cursor = dh.getHosts(uid);

            if (cursor.moveToFirst()) {
                do {
                    String host = cursor.getString(cursor.getColumnIndex("daddr"));
                    if (findTracker(host) == null)
                        continue;

                    InetAddress ipAddress = InetAddress.getByName(host);
                    CountryResponse response = reader.country(ipAddress);

                    Country country = response.getCountry();
                    String code = country.getIsoCode();
                    if (code == null)
                        continue;

                    Integer count = countryToCount.get(code);
                    if (count == null) {
                        countryToCount.put(code, 1);
                    } else {
                        countryToCount.put(code, count + 1);
                    }
                } while (cursor.moveToNext());
            }

            cursor.close();
        } catch (IOException | GeoIp2Exception e) {
            e.printStackTrace();
        }

        return countryToCount;
    }

    @Override
    public void onViewCreated(final View v, Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);

        Bundle arguments = getArguments();
        mAppUid = arguments.getInt(ARG_APP_UID);

        ProgressBar pbLoading = v.findViewById(R.id.pbLoading);
        GeoMapView mv = v.findViewById(R.id.map_view);

        mv.setOnInitializedListener(geoMapView1 -> {
            Handler mHandler = new Handler();
            new Thread(() -> {
                final Map<String, Integer> hostCountriesCount = getHostCountriesCount(mAppUid);
                // run on UI
                mHandler.post(() -> {
                    for (String code : hostCountriesCount.keySet()) {
                        mv.setCountryColor(code, "#B71C1C");
                    }
                    mv.refresh();
                    mv.setVisibility(View.VISIBLE);
                    pbLoading.setVisibility(View.GONE);
                });
            }).start();
        });
    }
}
