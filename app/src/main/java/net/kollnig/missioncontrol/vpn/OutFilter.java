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

import java.nio.ByteBuffer;

import edu.uci.calit2.antmonitor.lib.logging.ConnectionValue;
import edu.uci.calit2.antmonitor.lib.logging.PacketAnnotation;
import edu.uci.calit2.antmonitor.lib.util.IpDatagram;
import edu.uci.calit2.antmonitor.lib.vpn.OutPacketFilter;

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

		ConnectionValue v = mapDatagramToApp(packet);
		String appId = v.getAppName();
		if (appId == null)
			return ALLOW;

		if (appBlocklist.blockedApp(appId)
				&& appBlocklist.blockedTracker(appId, tracker.getRoot())
		)
			return BLOCK;

		// DATABASE.logPacketAsyncTask(mContext, appId, remoteIp, hostname);
		return ALLOW;
	}
}
