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

import net.kollnig.missioncontrol.Common;
import net.kollnig.missioncontrol.data.Database;

import java.nio.ByteBuffer;

import edu.uci.calit2.antmonitor.lib.logging.ConnectionValue;
import edu.uci.calit2.antmonitor.lib.logging.PacketConsumer;
import edu.uci.calit2.antmonitor.lib.logging.PacketProcessor.TrafficType;
import edu.uci.calit2.antmonitor.lib.util.IpDatagram;
import edu.uci.calit2.antmonitor.lib.util.PacketDumpInfo;
import edu.uci.calit2.antmonitor.lib.vpn.VpnController;

public class OutConsumer extends PacketConsumer {
	static PackageManager pm;
	private final Context mContext;
	static Database database;
	static ConnectivityManager connectivityManager;
	private static String TAG = OutConsumer.class.getSimpleName();

	public OutConsumer (Context c, TrafficType trafficType) {
		super(c, trafficType, null);
		database = Database.getInstance(c);
		mContext = c;

		connectivityManager = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
		pm = c.getPackageManager();
	}

	static String lookupHostname (String hostname) {
		return VpnController.retrieveHostname(hostname);
	}


	/**
	 * Logs outgoing packets of apps.
	 *
	 * @param packetDumpInfo The outgoing packet.
	 */
	@Override
	protected void consumePacket (PacketDumpInfo packetDumpInfo) {
		// Parse IP packet
		ByteBuffer packet = ByteBuffer.wrap(packetDumpInfo.getDump());

		// Identify sending app
		String appname;
		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
			appname = Common.getAppNameQ(packet, connectivityManager, pm);
		} else {
			ConnectionValue cv = mapPacketToApp(packetDumpInfo);
			appname = cv.getAppName();
		}
		if (appname == null || appname.startsWith(ConnectionValue.MappingErrors.PREFIX))
			return;

		// Log packet in database
		String remoteIp = IpDatagram.readDestinationIP(packet);
		String hostname = lookupHostname(remoteIp);
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
}
