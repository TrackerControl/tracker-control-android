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
import android.util.Log;

import net.kollnig.missioncontrol.data.Database;
import net.kollnig.missioncontrol.main.AppBlocklistController;

import java.nio.ByteBuffer;

import edu.uci.calit2.antmonitor.lib.logging.ConnectionValue;
import edu.uci.calit2.antmonitor.lib.logging.PacketConsumer;
import edu.uci.calit2.antmonitor.lib.logging.PacketProcessor.TrafficType;
import edu.uci.calit2.antmonitor.lib.util.IpDatagram;
import edu.uci.calit2.antmonitor.lib.util.PacketDumpInfo;
import edu.uci.calit2.antmonitor.lib.vpn.VpnController;

public class OutConsumer extends PacketConsumer {
	private final String TAG = OutConsumer.class.getSimpleName();
	private final Context mContext;
	private final AppBlocklistController appBlocklist;
	private Database database;

	public OutConsumer (Context c, TrafficType trafficType) {
		super(c, trafficType, null);
		database = Database.getInstance(c);
		mContext = c;
		appBlocklist = AppBlocklistController.getInstance(c);
	}

	static String getHostname (String remoteIp) {
		return VpnController.retrieveHostname(remoteIp);
	}

	/**
	 * Logs packets of apps, if:
	 * - TCP packet
	 * - no system app
	 *
	 * @param packetDumpInfo The outgoing packet.
	 */
	@Override
	protected void consumePacket (PacketDumpInfo packetDumpInfo) {
		// Identify sending app
		ConnectionValue cv = mapPacketToApp(packetDumpInfo);
		String appname = cv.getAppName();
		if (appname.startsWith(ConnectionValue.MappingErrors.PREFIX)
				|| (appBlocklist.systemApps.contains(cv.getAppName())))
			return;

		// Parse IP packet
		byte[] packet = packetDumpInfo.getDump();
		IpDatagram ipDatagram = new IpDatagram(ByteBuffer.wrap(packet));

		// Restrict logging to TCP
		/*short protocol = ipDatagram.getProtocol();
		if (protocol != IpDatagram.TCP) return;*/

		// Log packet in database
		String remoteIp = ipDatagram.getDestinationIP().getHostAddress();
		String hostname = getHostname(remoteIp);
		if (hostname == null) hostname = remoteIp;
		database.logPacketAsyncTask(mContext, appname, remoteIp, hostname);

		if (remoteIp.equals("8.8.8.8")) {
			Log.d(TAG, remoteIp);
		}
	}

	/**
	 * Gets called when the VPN connection is being torn down. Use this method to perform any
	 * clean-up needed with any logged files and etc.
	 */
	@Override
	protected void onStop () {

	}
}
