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
import android.graphics.Picture;
import android.graphics.drawable.PictureDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.collection.ArrayMap;
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

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
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

        Cursor cursor = null;
        try {
            Context context = getContext();
            if (context == null)
                return countryToCount;

            InputStream database = context.getAssets().open("GeoLite2-Country.mmdb");
            DatabaseReader reader = new DatabaseReader.Builder(database).withCache(new CHMCache()).build();

            DatabaseHelper dh = DatabaseHelper.getInstance(getContext());
            cursor = dh.getHosts(uid);

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
        } catch (IOException | GeoIp2Exception e) {
            e.printStackTrace();
        } finally {
            if (cursor != null)
                cursor.close();
        }
        return countryToCount;
    }

    @Override
    public void onViewCreated(final View v, Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);

        Bundle arguments = getArguments();
        mAppUid = arguments.getInt(ARG_APP_UID);

        ProgressBar pbLoading = v.findViewById(R.id.pbLoading);

        ImageView mv = v.findViewById(R.id.svgView);
        TextView txtFailure = v.findViewById(R.id.txtFailure);

        new Thread(() -> {
            final int success;
            try {
                SVG svg = SVG.getFromAsset(requireContext().getAssets(), "world.svg");

                Map<String, Integer> hostCountriesCount = getHostCountriesCount(mAppUid);

                final RenderOptions renderOptions = new RenderOptions();
                String countries = TextUtils.join(",#", hostCountriesCount.keySet());
                renderOptions.css(String.format("#%s { fill: #B71C1C; }", countries.toUpperCase()));

                mv.post(() -> {
                    Picture picture = svg.renderToPicture(renderOptions);
                    mv.setImageDrawable(new PictureDrawable(picture));
                    pbLoading.setVisibility(View.GONE);
                });
            } catch (IllegalStateException | IOException | SVGParseException e) {
                e.printStackTrace();

                mv.post(() -> {
                    mv.setVisibility(View.GONE);
                    txtFailure.setVisibility(View.VISIBLE);
                    pbLoading.setVisibility(View.GONE);
                });
            }
        }).start();
    }
}
