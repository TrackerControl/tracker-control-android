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

import static net.kollnig.missioncontrol.data.TrackerList.findTracker;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Picture;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.collection.ArrayMap;
import androidx.compose.ui.platform.ComposeView;
import androidx.fragment.app.Fragment;

import com.caverock.androidsvg.RenderOptions;
import com.caverock.androidsvg.SVG;
import com.caverock.androidsvg.SVGParseException;
import com.maxmind.db.CHMCache;
import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.exception.GeoIp2Exception;
import com.maxmind.geoip2.model.CountryResponse;
import com.maxmind.geoip2.record.Country;

import net.kollnig.missioncontrol.R;
import net.kollnig.missioncontrol.ui.compose.CountriesScreen;
import net.kollnig.missioncontrol.ui.compose.CountriesScreenController;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;

import eu.faircode.netguard.DatabaseHelper;

public class CountriesFragment extends Fragment {
    private static final String ARG_APP_UID = "app-uid";
    private final String TAG = CountriesFragment.class.getSimpleName();
    private int mAppUid;
    private CountriesScreenController screenController;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private int viewGeneration;

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
    }

    /**
     * Retrieves information about all seen trackers
     *
     * @return A list of seen trackers
     */
    public synchronized Map<String, Integer> getHostCountriesCount(int uid) {
        Context context = getContext();
        if (context == null)
            return new ArrayMap<>();
        return getHostCountriesCount(context, uid);
    }

    private synchronized Map<String, Integer> getHostCountriesCount(Context context, int uid) {
        Map<String, Integer> countryToCount = new ArrayMap<>();

        DatabaseHelper dh = DatabaseHelper.getInstance(context);
        try (Cursor cursor = dh.getHosts(uid)) {
            InputStream database = context.getAssets().open("GeoLite2-Country.mmdb");
            try (DatabaseReader reader = new DatabaseReader.Builder(database).withCache(new CHMCache()).build()) {

                if (cursor.moveToFirst()) {
                    do {
                        String host = cursor.getString(cursor.getColumnIndexOrThrow("daddr"));
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
            }
        } catch (IOException | GeoIp2Exception e) {
            e.printStackTrace();
        }
        return countryToCount;
    }

    @Override
    public void onViewCreated(@NonNull final View v, Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);

        Bundle arguments = getArguments();
        assert arguments != null;
        mAppUid = arguments.getInt(ARG_APP_UID);

        ComposeView composeCountries = v.findViewById(R.id.composeCountries);
        screenController = CountriesScreen.install(composeCountries);
        int generation = ++viewGeneration;
        Context context = requireContext().getApplicationContext();
        int uid = mAppUid;
        new Thread(() -> loadCountriesMap(context, uid, generation)).start();
    }

    /**
     * Load the country map while Compose owns only the presentation state.
     */
    private void loadCountriesMap(Context context, int uid, int generation) {
        try {
            SVG svg = SVG.getFromAsset(context.getAssets(), "world.svg");

            Map<String, Integer> hostCountriesCount = getHostCountriesCount(context, uid);

            final RenderOptions renderOptions = new RenderOptions();
            String countries = TextUtils.join(",#", hostCountriesCount.keySet());
            renderOptions.css(String.format("#%s { fill: #B71C1C; }", countries.toUpperCase()));

            // Render on this background thread; the resulting Picture is
            // immutable and safe to hand to the UI thread. This also means a
            // malformed-CSS exception is caught by the outer try below rather
            // than crashing the UI thread.
            final Picture picture = svg.renderToPicture(renderOptions);
            ArrayList<String> countryCodes = new ArrayList<>(hostCountriesCount.keySet());
            Collections.sort(countryCodes);
            postMap(picture, TextUtils.join(", ", countryCodes), generation);
        } catch (IllegalStateException | IOException | SVGParseException e) {
            e.printStackTrace();
            postFailure(generation);
        }
    }

    private void postMap(Picture picture, String countryCodes, int generation) {
        mainHandler.post(() -> {
            if (generation == viewGeneration && screenController != null)
                screenController.showMap(picture, countryCodes);
        });
    }

    private void postFailure(int generation) {
        mainHandler.post(() -> {
            if (generation == viewGeneration && screenController != null)
                screenController.showFailure();
        });
    }

    @Override
    public void onDestroyView() {
        viewGeneration++;
        screenController = null;
        super.onDestroyView();
    }
}
