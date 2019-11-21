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

import android.annotation.TargetApi;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Build;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.List;

import javax.annotation.Nullable;

import edu.uci.calit2.antmonitor.lib.logging.ConnectionValue;
import edu.uci.calit2.antmonitor.lib.util.IpDatagram;

import static android.os.Process.INVALID_UID;
import static android.system.OsConstants.IPPROTO_TCP;
import static android.system.OsConstants.IPPROTO_UDP;

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

	/**
	 * Retrieves the name of the app based on the given uid
	 *
	 * @param uid - of the app
	 * @return the name of the package of the app with the given uid, or "Unknown" if
	 * no name could be found for the uid.
	 */
	static String getAppName (PackageManager pm, int uid) {
		/* IMPORTANT NOTE:
		 * From https://source.android.com/devices/tech/security/ : "The Android
		 * system assigns a unique user ID (UID) to each Android application and
		 * runs it as that user in a separate process"
		 *
		 * However, there is an exception: "A closer relationship with a shared
		 * Application Sandbox is allowed via the shared UID feature where two
		 * or more applications signed with same developer key can declare a
		 * shared UID in their manifest."
		 */

		// See if this is root
		if (uid == 0)
			return "System";

		// If we can't find a running app, just get a list of packages that map to the uid
		String[] packages = pm.getPackagesForUid(uid);
		if (packages != null && packages.length > 0)
			return packages[0];

		return "Unknown";
	}

	@TargetApi(Build.VERSION_CODES.Q)
	public static String getAppNameQ (final ByteBuffer packet,
	                                  ConnectivityManager connectivityManager,
	                                  PackageManager pm) {
		// Only UDP and TCP are supported
		short protocol = IpDatagram.readProtocol(packet);
		if (protocol != IpDatagram.UDP && protocol != IpDatagram.TCP)
			return ConnectionValue.MappingErrors.PREFIX + "Unsupported protocol.";

		int lookupProtocol = (protocol == IpDatagram.TCP) ? IPPROTO_TCP : IPPROTO_UDP;

		InetSocketAddress local, remote;
		try {
			local = new InetSocketAddress
					(IpDatagram.readSourceIP(packet), IpDatagram.readSourcePort(packet));
			remote = new InetSocketAddress
					(IpDatagram.readDestinationIP(packet), IpDatagram.readDestinationPort(packet));
		} catch (UnknownHostException e) {
			return ConnectionValue.MappingErrors.PREFIX + "Resolving host failed.";
		}

		int uid = connectivityManager.getConnectionOwnerUid(lookupProtocol, local, remote);
		if (uid == INVALID_UID)
			return ConnectionValue.MappingErrors.PREFIX + "INVALID_UID in ConnectivityManager.";

		return getAppName(pm, uid);
	}


	/**
	 * Check if the {@code FLAG_SYSTEM} or {@code FLAG_UPDATED_SYSTEM_APP} is set for the application.
	 *
	 * @param applicationInfo The application to check.
	 * @return true if the {@code applicationInfo} belongs to a system or updated system application.
	 */
	public static boolean isSystemPackage (ApplicationInfo applicationInfo) {
		return (((applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0)
				|| (applicationInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0);
	}
}
