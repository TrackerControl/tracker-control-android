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
 * Copyright (C) 2020 Konrad Kollnig, University of Oxford
 */

package net.kollnig.missioncontrol.details;

import android.database.Cursor;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Button;
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
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import eu.faircode.netguard.DatabaseHelper;

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
            InputStream database = getContext().getAssets().open("GeoLite2-Country.mmdb");
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

        // Load arguments
        Bundle arguments = getArguments();
        mAppUid = arguments.getInt(ARG_APP_UID);

        ProgressBar pbLoading = v.findViewById(R.id.pbLoading);
        Button btnLoad = v.findViewById(R.id.btnLoad);
        WebView wv = v.findViewById(R.id.web_view);

        btnLoad.setOnClickListener(v1 -> {
            pbLoading.setVisibility(View.VISIBLE);

            Handler mHandler = new Handler();
            new Thread(() -> {
                final Map<String, Integer> hostCountriesCount = getHostCountriesCount(mAppUid);

                // run on UI
                mHandler.post(() -> {
                    CountryCodeConverter ccC = new CountryCodeConverter();

                    String rows = "";
                    int n = 0;
                    for (String code : hostCountriesCount.keySet()) {
                        if (n > 0)
                            rows += ",";
                        rows += "['" + ccC.iso2CountryCodeToName(code) + "'," + hostCountriesCount.get(code) + "]";
                        n++;
                    }

                    String html = "<html>" +
                            "<head>" +
                            "<script type='text/javascript' src='https://www.google.com/jsapi'></script>" +
                            "<script type='text/javascript'>" +
                            "google.load('visualization', '1', {'packages': ['geochart']});" +
                            "google.setOnLoadCallback(drawRegionsMap);" +
                            "function drawRegionsMap() {" +
                            "var data = new google.visualization.DataTable();" +
                            "data.addColumn('string', 'Country');" +
                            "data.addColumn('number', 'Number of Hosts');" +
                            "data.addRows([" + rows + "]);" +
                            "var options = {colorAxis: {colors: ['#398239', '#ca0300']}};" +
                            "var chart = new google.visualization.GeoChart(document.getElementById('chart_div'));" +
                            "chart.draw(data, options); };" +
                            "</script>" +
                            "</head>" +
                            "<body style='padding: 10px 0;'>" +
                            "<div id='chart_div' style='width: 100%;'></div>" +
                            "</body>" +
                            "</html> ";
                    wv.getSettings().setJavaScriptEnabled(true);
                    wv.loadDataWithBaseURL("", html, "text/html", "UTF-8", "");

                    wv.setVisibility(View.VISIBLE);
                    pbLoading.setVisibility(View.GONE);
                    btnLoad.setVisibility(View.GONE);
                });
            }).start();
        });
    }

    // Taken From.
    // https://blog.oio.de/2010/12/31/mapping-iso2-and-iso3-country-codes-with-java/
    private static class CountryCodeConverter {

        private Map<String, Locale> localeMap;

        public CountryCodeConverter() {
            initCountryCodeMapping();
        }

        private void initCountryCodeMapping() {
            String[] countries = Locale.getISOCountries();
            localeMap = new HashMap<>(countries.length);
            for (String country : countries) {
                Locale locale = new Locale("", country);
                localeMap.put(locale.getISO3Country().toUpperCase(), locale);
            }
        }

        public String iso3CountryCodeToIso2CountryCode(String iso3CountryCode) {
            Locale code = localeMap.get(iso3CountryCode);
            if (code == null) {
                return "";
            }
            return code.getCountry();
        }

        public String iso2CountryCodeToIso3CountryCode(String iso2CountryCode) {
            Locale locale = new Locale("", iso2CountryCode);
            return locale.getISO3Country();
        }

        public String iso2CountryCodeToName(String iso2CountryCode) {
            Locale locale = new Locale("", iso2CountryCode);
            return locale.getDisplayName();
        }
    }
}
