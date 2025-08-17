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
import android.app.Activity;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.StrictMode;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.OnApplyWindowInsetsListener;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.preference.PreferenceManager;

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
            CoreConfigurationBuilder builder = new CoreConfigurationBuilder();
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
                .withReportFormat(KEY_VALUE_LIST)
                .withPluginConfigurations(
                        new MailSenderConfigurationBuilder()
                            .withMailTo("crash@trackercontrol.org")
                            .withBody(getString(R.string.crash_body))
                            .withReportAsFile(true)
                            .withReportFileName("tracker-control-crash.json")
                            .withEnabled(true)
                            .build(),
                        new DialogConfigurationBuilder()
                            .withText(getString(R.string.crash_dialog_text))
                            .withCommentPrompt(getString(R.string.crash_dialog_comment))
                            .withEnabled(true)
                            .build()
                );

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

        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    View content = activity.findViewById(android.R.id.content);
                    ViewCompat.setOnApplyWindowInsetsListener(content, new OnApplyWindowInsetsListener() {
                        @NonNull
                        @Override
                        public WindowInsetsCompat onApplyWindowInsets(@NonNull View v, @NonNull WindowInsetsCompat insets) {
                            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());

                            TypedValue tv = new TypedValue();
                            activity.getTheme().resolveAttribute(R.attr.colorPrimaryDark, tv, true);

                            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
                            boolean dark = prefs.getBoolean("dark_theme", false);

                            activity.getWindow().getDecorView().setBackgroundColor(tv.data);
                            content.setBackgroundColor(dark ? Color.parseColor("#ff121212") : Color.WHITE);

                            int actionBarHeight = Util.dips2pixels(56, activity);
                            View decor = activity.getWindow().getDecorView();
                            WindowCompat.getInsetsController(activity.getWindow(), decor).setAppearanceLightStatusBars(false);
                            WindowCompat.getInsetsController(activity.getWindow(), decor).setAppearanceLightNavigationBars(!dark);
                            v.setPadding(bars.left, bars.top + actionBarHeight, bars.right, bars.bottom);

                            return insets;
                        }
                    });
                }
            }

            @Override
            public void onActivityStarted(@NonNull Activity activity) {

            }

            @Override
            public void onActivityResumed(@NonNull Activity activity) {

            }

            @Override
            public void onActivityPaused(@NonNull Activity activity) {

            }

            @Override
            public void onActivityStopped(@NonNull Activity activity) {

            }

            @Override
            public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {

            }

            @Override
            public void onActivityDestroyed(@NonNull Activity activity) {

            }
        });
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