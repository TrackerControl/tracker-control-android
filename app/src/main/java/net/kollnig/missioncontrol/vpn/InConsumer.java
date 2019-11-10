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
package net.kollnig.missioncontrol.vpn;

import android.content.Context;
import android.util.Log;

import java.nio.ByteBuffer;

import edu.uci.calit2.antmonitor.lib.logging.PacketConsumer;
import edu.uci.calit2.antmonitor.lib.logging.PacketProcessor.TrafficType;
import edu.uci.calit2.antmonitor.lib.util.IpDatagram;
import edu.uci.calit2.antmonitor.lib.util.PacketDumpInfo;

public class InConsumer extends PacketConsumer {
	private final String TAG = InConsumer.class.getSimpleName();
	private final Context mContext;

	public InConsumer (Context c, TrafficType trafficType) {
		super(c, trafficType, null);
		mContext = c;
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
		// Parse IP packet
		byte[] packet = packetDumpInfo.getDump();
		IpDatagram ipDatagram = new IpDatagram(ByteBuffer.wrap(packet));
		String remoteIp = ipDatagram.getSourceIP().getHostAddress();

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
