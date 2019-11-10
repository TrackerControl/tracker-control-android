/*
 * Copyright (C) 2019 Konrad Kollnig, University of Oxford
 *
 * Tracker Control is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * Tracker Control is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Tracker Control. If not, see <http://www.gnu.org/licenses/>.
 */

package net.kollnig.missioncontrol;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

import javax.annotation.Nullable;

public class Common {
	@Nullable
	public static String fetch (String url) {
		try {
			StringBuilder html = new StringBuilder();

			HttpURLConnection conn = (HttpURLConnection) (new URL(url)).openConnection();
			conn.setConnectTimeout(5000);
			BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));

			String str;
			while ((str = in.readLine()) != null)
				html.append(str);

			in.close();

			return html.toString();
		} catch (IOException e) {
			return null;
		}
	}

	public static Intent adSettings () {
		Intent intent = new Intent();
		return intent.setComponent(new ComponentName("com.google.android.gms", "com.google.android.gms.ads.settings.AdsSettingsActivity"));
	}

	public static Intent browse (String url) {
		if (!url.startsWith("http://") && !url.startsWith("https://"))
			url = "http://" + url;

		return new Intent(Intent.ACTION_VIEW, Uri.parse(url));
	}

	public static boolean hasAdSettings (Context c) {
		return isCallable(c, adSettings());
	}

	public static boolean isCallable (Context c, Intent intent) {
		List<ResolveInfo> list = c.getPackageManager().queryIntentActivities(intent,
				PackageManager.MATCH_DEFAULT_ONLY);
		return list.size() > 0;
	}
}
