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

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.util.Log;

import net.kollnig.missioncontrol.R;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/**
 * <p>
 * A {@link android.content.BroadcastReceiver} that is invoked when the device has booted. It
 * attempts to reestablish the VPN connection on boot so that data collection may continue after
 * a reboot.
 * </p>
 *
 * @author Simon Langhoff, Janus Varmarken
 */
public class DeviceBootListener extends BroadcastReceiver {

	private final String mClassTag = this.getClass().getSimpleName();

	static final String VPN_ACTION_NOTIFICATION = "edu.uci.calit2.anteater.ACTION.NOTIFICATION";
	final String CHANNEL_ID = "ant_channel_02";

	@Override
	public void onReceive (Context context, Intent intent) {
		if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
			Log.d(mClassTag, "Boot completed, attempting to restart VPN.");

			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
				// Launch invisible activity (only way to request VPN rights)
				Intent vpnStartRequest = new Intent(context, VpnStarterActivity.class);
				vpnStartRequest.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
				context.startActivity(vpnStartRequest);
			} else {
				// TODO Find solution for Android 10
				createNotificationChannel(context);

				Intent notificationIntent = new Intent(VPN_ACTION_NOTIFICATION);
				notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
				PendingIntent pendingIntent = PendingIntent.getActivity(context, 0,
						notificationIntent, 0);

				Bitmap icon = BitmapFactory.decodeResource(context.getResources(),
						edu.uci.calit2.antmonitor.lib.R.mipmap.ic_launcher);

				NotificationCompat.Builder notifBuilder = new NotificationCompat.Builder(context, CHANNEL_ID)
						.setSmallIcon(edu.uci.calit2.antmonitor.lib.R.mipmap.shield)
						.setContentTitle(context.getResources().getString(edu.uci.calit2.antmonitor.lib.R.string.app_name))
						.setContentText("Open to restart")
						.setPriority(NotificationCompat.PRIORITY_DEFAULT)
						.setContentIntent(pendingIntent)
						.setAutoCancel(true)
						.setLargeIcon(Bitmap.createScaledBitmap(icon, 128, 128, false));
				notifBuilder.build();

				NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
				notificationManager.notify(24, notifBuilder.build());

			}
		}
	}

	private void createNotificationChannel (Context context) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			CharSequence name = context.getString(R.string.afterboot_notification);
			int importance = NotificationManager.IMPORTANCE_DEFAULT;
			NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
			NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
			notificationManager.createNotificationChannel(channel);
		}
	}
}
