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
import net.kollnig.missioncontrol.Common;
import net.kollnig.missioncontrol.data.Company;
import net.kollnig.missioncontrol.data.Database;
import net.kollnig.missioncontrol.main.AppBlocklistController;

import java.nio.ByteBuffer;

import edu.uci.calit2.antmonitor.lib.logging.ConnectionValue;
import edu.uci.calit2.antmonitor.lib.logging.PacketAnnotation;
import edu.uci.calit2.antmonitor.lib.util.IpDatagram;
import edu.uci.calit2.antmonitor.lib.vpn.OutPacketFilter;

import static net.kollnig.missioncontrol.vpn.OutConsumer.connectivityManager;
import static net.kollnig.missioncontrol.vpn.OutConsumer.database;
import static net.kollnig.missioncontrol.vpn.OutConsumer.pm;

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
		String hostname = OutConsumer.lookupHostname(remoteIp);
		if (hostname == null)
			return ALLOW;

		Company tracker = Database.getCompany(hostname);
		if (tracker == null)
			return ALLOW;

		// Identify sending app
		String appname;
		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
			appname = Common.getAppNameQ(packet, connectivityManager, pm);
		} else {
			ConnectionValue cv = mapDatagramToApp(packet);
			appname = cv.getAppName();
		}
		if (appname == null || appname.startsWith(ConnectionValue.MappingErrors.PREFIX))
			return ALLOW;

		if (appBlocklist.blockedApp(appname)
				&& appBlocklist.blockedTracker(appname, tracker.getRoot())
		) {
			database.logPacketAsyncTask(mContext, appname, remoteIp, hostname);
			return BLOCK;
		}

		return ALLOW;
	}
}
