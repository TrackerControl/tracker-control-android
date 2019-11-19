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

import net.kollnig.missioncontrol.BuildConfig;
import net.kollnig.missioncontrol.data.Company;
import net.kollnig.missioncontrol.data.Database;
import net.kollnig.missioncontrol.main.AppBlocklistController;

import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;

import edu.uci.calit2.antmonitor.lib.logging.ConnectionValue;
import edu.uci.calit2.antmonitor.lib.logging.PacketAnnotation;
import edu.uci.calit2.antmonitor.lib.util.IpDatagram;
import edu.uci.calit2.antmonitor.lib.vpn.OutPacketFilter;

import static android.os.Process.INVALID_UID;
import static android.system.OsConstants.IPPROTO_TCP;
import static android.system.OsConstants.IPPROTO_UDP;
import static net.kollnig.missioncontrol.vpn.OutConsumer.connectivityManager;
import static net.kollnig.missioncontrol.vpn.OutConsumer.getAppName;

public class OutFilter extends OutPacketFilter {
	private final String TAG = OutFilter.class.getSimpleName();
	private final AppBlocklistController appBlocklist;

	private final PacketAnnotation ALLOW = new PacketAnnotation(true);
	private final PacketAnnotation BLOCK = new PacketAnnotation(false);

	public OutFilter (Context c) {
		super(c);

		appBlocklist = AppBlocklistController.getInstance(c);
	}

	/**
	 * Accepts packets that do not chosen to be blocked by the user.
	 *
	 * @param packet - the IP datagram to inspect
	 */
	@Override
	public PacketAnnotation acceptIPDatagram (final ByteBuffer packet) {
		if (BuildConfig.FLAVOR.equals("play"))
			return ALLOW;

		String remoteIp = IpDatagram.readDestinationIP(packet);
		String hostname = OutConsumer.getHostname(remoteIp);
		if (hostname == null)
			return ALLOW;

		Company tracker = Database.getCompany(hostname);
		if (tracker == null)
			return ALLOW;

		String appname;
		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
			// Only UDP and TCP are supported
			short protocol = IpDatagram.readProtocol(packet);
			if (protocol != IpDatagram.UDP && protocol != IpDatagram.TCP)
				return ALLOW;

			int lookupProtocol = (protocol == IpDatagram.TCP) ? IPPROTO_TCP : IPPROTO_UDP;

			InetSocketAddress local, remote;
			try {
				local = new InetSocketAddress
						(IpDatagram.readSourceIP(packet), IpDatagram.readSourcePort(packet));
				remote = new InetSocketAddress
						(IpDatagram.readDestinationIP(packet), IpDatagram.readDestinationPort(packet));
			} catch (UnknownHostException e) {
				return ALLOW;
			}

			int uid = connectivityManager.getConnectionOwnerUid(lookupProtocol, local, remote);
			if (uid == INVALID_UID)
				return ALLOW;

			appname = getAppName(uid);
		} else {
			ConnectionValue cv = mapDatagramToApp(packet);
			appname = cv.getAppName();
		}
		if (appname == null)
			return ALLOW;

		if (appBlocklist.blockedApp(appname)
				&& appBlocklist.blockedTracker(appname, tracker.getRoot())
		)
			return BLOCK;

		// DATABASE.logPacketAsyncTask(mContext, appname, remoteIp, hostname);
		return ALLOW;
	}
}
