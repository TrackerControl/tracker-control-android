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
package net.kollnig.missioncontrol.vpn;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.util.Log;

import net.kollnig.missioncontrol.data.Database;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

import edu.uci.calit2.antmonitor.lib.logging.ConnectionValue;
import edu.uci.calit2.antmonitor.lib.logging.PacketConsumer;
import edu.uci.calit2.antmonitor.lib.logging.PacketProcessor.TrafficType;
import edu.uci.calit2.antmonitor.lib.util.IpDatagram;
import edu.uci.calit2.antmonitor.lib.util.PacketDumpInfo;
import edu.uci.calit2.antmonitor.lib.vpn.VpnController;

import static android.os.Process.INVALID_UID;
import static android.system.OsConstants.IPPROTO_TCP;
import static android.system.OsConstants.IPPROTO_UDP;

public class OutConsumer extends PacketConsumer {
	static PackageManager pm;
	private final Context mContext;
	private final Database database;
	static ConnectivityManager connectivityManager;
	private static String TAG = OutConsumer.class.getSimpleName();

	public OutConsumer (Context c, TrafficType trafficType) {
		super(c, trafficType, null);
		database = Database.getInstance(c);
		mContext = c;

		connectivityManager = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
		pm = c.getPackageManager();
	}

	static String getHostname (String remoteIp) {
		return VpnController.retrieveHostname(remoteIp);
	}


	/**
	 * Logs outgoing packets of apps.
	 *
	 * @param packetDumpInfo The outgoing packet.
	 */
	@Override
	protected void consumePacket (PacketDumpInfo packetDumpInfo) {
		// Parse IP packet
		byte[] packet = packetDumpInfo.getDump();
		IpDatagram ipDatagram = new IpDatagram(ByteBuffer.wrap(packet));

		// Identify sending app
		String appname;
		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
			// Only UDP and TCP are supported
			short protocol = ipDatagram.getProtocol();
			if (protocol != IpDatagram.UDP && protocol != IpDatagram.TCP)
				return;

			int lookupProtocol = (protocol == IpDatagram.TCP) ? IPPROTO_TCP : IPPROTO_UDP;

			InetSocketAddress local = new InetSocketAddress
					(ipDatagram.getSourceIP(), IpDatagram.readSourcePort(packet));
			InetSocketAddress remote = new InetSocketAddress
					(ipDatagram.getDestinationIP(), IpDatagram.readDestinationPort(packet));

			int uid = connectivityManager.getConnectionOwnerUid(lookupProtocol, local, remote);
			if (uid == INVALID_UID)
				return;

			appname = getAppName(uid);
		} else {
			ConnectionValue cv = mapPacketToApp(packetDumpInfo);
			appname = cv.getAppName();
			if (appname.startsWith
					(ConnectionValue.MappingErrors.PREFIX)) {
				return;
			}
		}

		// Log packet in database
		String remoteIp = ipDatagram.getDestinationIP().getHostAddress();
		String hostname = getHostname(remoteIp);
		if (hostname == null) hostname = remoteIp;
		database.logPacketAsyncTask(mContext, appname, remoteIp, hostname);
	}

	/**
	 * Gets called when the VPN connection is being torn down. Use this method to perform any
	 * clean-up needed with any logged files and etc.
	 */
	@Override
	protected void onStop () {

	}

	/**
	 * Retrieves the name of the app based on the given uid
	 *
	 * @param uid - of the app
	 * @return the name of the package of the app with the given uid, or "Unknown" if
	 * no name could be found for the uid.
	 */
	static String getAppName (int uid) {
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

		Log.w(TAG, "Process with uid " + uid + " does not appear to be running!");
		return "Unknown";
	}
}
