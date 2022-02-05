/*
 * This file is from NetGuard.
 *
 * NetGuard is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NetGuard is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NetGuard.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright © 2015–2020 by Marcel Bokhorst (M66B), Konrad
 * Kollnig (University of Oxford)
 */

package eu.faircode.netguard;

import static org.acra.data.StringFormat.KEY_VALUE_LIST;

import android.annotation.TargetApi;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.os.StrictMode;
import android.util.Log;

import net.kollnig.missioncontrol.BuildConfig;
import net.kollnig.missioncontrol.R;

import org.acra.ACRA;
import org.acra.ReportField;
import org.acra.config.CoreConfigurationBuilder;
import org.acra.config.DialogConfigurationBuilder;
import org.acra.config.MailSenderConfigurationBuilder;

public class ApplicationEx extends Application {
    private static final String TAG = "TrackerControl.App";

    @Override
    protected void attachBaseContext (Context base) {
        super.attachBaseContext(base);

        try {
            CoreConfigurationBuilder builder = new CoreConfigurationBuilder(this);
            builder
                .withReportContent( // limit collected data
                    ReportField.USER_COMMENT,
                    ReportField.USER_APP_START_DATE,
                    ReportField.USER_CRASH_DATE,
                    ReportField.ANDROID_VERSION,
                    ReportField.BUILD_CONFIG,
                    ReportField.STACK_TRACE,
                    ReportField.STACK_TRACE_HASH,
                    ReportField.AVAILABLE_MEM_SIZE,
                    ReportField.TOTAL_MEM_SIZE)
                .withReportFormat(KEY_VALUE_LIST);

            builder.getPluginConfigurationBuilder(MailSenderConfigurationBuilder.class)
                    .withMailTo("crash@trackercontrol.org")
                    .withResBody(R.string.crash_body)
                    .withReportAsFile(true)
                    .withReportFileName("tracker-control-crash.json")
                    .withEnabled(true);

            builder.getPluginConfigurationBuilder(DialogConfigurationBuilder.class)
                    .withResText(R.string.crash_dialog_text)
                    .withResCommentPrompt(R.string.crash_dialog_comment)
                    .withEnabled(true);

            ACRA.init(this, builder);

            if(BuildConfig.DEBUG) {
                //StrictMode.enableDefaults();
                //StrictMode.allowThreadDiskReads();
                StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder()
                        .detectLeakedSqlLiteObjects()
                        .detectLeakedClosableObjects()
                        .penaltyLog()
                        //.penaltyDeath()
                        .build());
                StrictMode.allowThreadDiskReads();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Create version=" + Util.getSelfVersionName(this) + "/" + Util.getSelfVersionCode(this));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            createNotificationChannels();
    }

    @TargetApi(Build.VERSION_CODES.O)
    private void createNotificationChannels() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        NotificationChannel foreground = new NotificationChannel("foreground", getString(R.string.channel_foreground), NotificationManager.IMPORTANCE_MIN);
        foreground.setSound(null, Notification.AUDIO_ATTRIBUTES_DEFAULT);
        nm.createNotificationChannel(foreground);

        NotificationChannel notify = new NotificationChannel("notify", getString(R.string.channel_notify), NotificationManager.IMPORTANCE_DEFAULT);
        notify.setSound(null, Notification.AUDIO_ATTRIBUTES_DEFAULT);
        notify.setBypassDnd(true);
        nm.createNotificationChannel(notify);

        NotificationChannel access = new NotificationChannel("access", getString(R.string.channel_access), NotificationManager.IMPORTANCE_DEFAULT);
        access.setSound(null, Notification.AUDIO_ATTRIBUTES_DEFAULT);
        notify.setBypassDnd(true);
        nm.createNotificationChannel(access);
    }
}