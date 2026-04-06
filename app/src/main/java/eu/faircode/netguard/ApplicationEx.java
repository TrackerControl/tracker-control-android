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
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.StrictMode;
import android.util.Log;
import android.view.View;

import androidx.activity.ComponentActivity;
import androidx.activity.EdgeToEdge;
import androidx.activity.SystemBarStyle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import net.kollnig.missioncontrol.BuildConfig;
import net.kollnig.missioncontrol.Common;
import net.kollnig.missioncontrol.R;
import net.kollnig.missioncontrol.data.BlockingMode;

import org.acra.ACRA;
import org.acra.ReportField;
import org.acra.config.CoreConfigurationBuilder;
import org.acra.config.DialogConfigurationBuilder;
import org.acra.config.MailSenderConfigurationBuilder;

public class ApplicationEx extends Application {
    private static final String TAG = "TrackerControl.App";

    @Override
    protected void attachBaseContext(Context base) {
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
                                    .build());

            ACRA.init(this, builder);

            if (BuildConfig.DEBUG) {
                // StrictMode.enableDefaults();
                // StrictMode.allowThreadDiskReads();
                StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder()
                        .detectLeakedSqlLiteObjects()
                        .detectLeakedClosableObjects()
                        .penaltyLog()
                        // .penaltyDeath()
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

        SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this);
        migratePreferences(prefs);

        // Keep VPN exclusions aligned with the selected blocking mode on startup.
        BlockingMode.syncModeExclusions(this);

        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
                if (activity instanceof ComponentActivity) {
                    int statusBarColor = ContextCompat.getColor(activity, R.color.colorPrimaryDark);
                    // SystemBarStyle.dark() is critical: without it, EdgeToEdge defaults to
                    // transparent, and M3's light theme produces white-on-white status bar icons.
                    EdgeToEdge.enable(
                            (ComponentActivity) activity,
                            SystemBarStyle.dark(statusBarColor),
                            SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT));

                    // Activities with windowActionBar=true use the theme's ActionBar
                    // and need manual inset handling: set the window background to the
                    // status bar color (visible through the transparent status bar on
                    // API 35+) and pad android.R.id.content to avoid drawing behind it.
                    // Activities with their own Toolbar (NoActionBar themes) handle
                    // insets themselves via AppBarLayout padding.
                    android.content.res.TypedArray a = activity.obtainStyledAttributes(
                            new int[]{androidx.appcompat.R.attr.windowActionBar});
                    boolean hasThemeActionBar = a.getBoolean(0, false);
                    a.recycle();

                    if (hasThemeActionBar) {
                        activity.getWindow().setBackgroundDrawable(new ColorDrawable(statusBarColor));

                        android.widget.FrameLayout content = activity.findViewById(android.R.id.content);
                        ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
                            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                                    | WindowInsetsCompat.Type.displayCutout());
                            v.setPadding(bars.left, bars.top, bars.right, 0);

                            // Set the actual layout background so only the status bar area
                            // shows the window background (primary dark color)
                            if (content.getChildCount() > 0) {
                                View child = content.getChildAt(0);
                                child.setBackgroundColor(
                                        Common.isNight(activity) ? Color.BLACK : Color.WHITE);
                            }
                            return insets;
                        });
                    }
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

    static void migratePreferences(SharedPreferences prefs) {
        if (prefs.contains("onboarding_complete") && !prefs.contains("onboarding_version")) {
            boolean completed = prefs.getBoolean("onboarding_complete", false);
            prefs.edit()
                    .remove("onboarding_complete")
                    .putInt("onboarding_version", completed ? 1 : 0)
                    .apply();
            Log.i(TAG, "Migrated onboarding_complete=" + completed + " -> onboarding_version=" + (completed ? 1 : 0));
        }

        if (!prefs.contains(BlockingMode.PREF_BLOCKING_MODE)) {
            Boolean oldStrict = prefs.contains("strict_blocking")
                    ? prefs.getBoolean("strict_blocking", false)
                    : null;
            int installedVersion = prefs.getInt("version", -1);
            String migratedMode = resolveBlockingModeMigration(null, oldStrict, installedVersion);

            if (oldStrict != null) {
                prefs.edit()
                        .remove("strict_blocking")
                        .putString(BlockingMode.PREF_BLOCKING_MODE, migratedMode)
                        .apply();
                Log.i(TAG, "Migrated strict_blocking=" + oldStrict + " -> mode=" + migratedMode);
            } else {
                prefs.edit().putString(BlockingMode.PREF_BLOCKING_MODE, migratedMode).apply();
            }
        }
    }

    static String resolveBlockingModeMigration(@Nullable String existingMode, @Nullable Boolean legacyStrict) {
        return resolveBlockingModeMigration(existingMode, legacyStrict, -1);
    }

    static String resolveBlockingModeMigration(@Nullable String existingMode,
            @Nullable Boolean legacyStrict,
            int installedVersion) {
        if (existingMode != null)
            return existingMode;
        if (legacyStrict != null)
            return legacyStrict ? BlockingMode.MODE_STRICT : BlockingMode.MODE_STANDARD;
        if (installedVersion >= 0)
            return BlockingMode.MODE_STANDARD;
        return BlockingMode.getDefaultMode();
    }

    @TargetApi(Build.VERSION_CODES.O)
    private void createNotificationChannels() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        NotificationChannel foreground = new NotificationChannel("foreground", getString(R.string.channel_foreground),
                NotificationManager.IMPORTANCE_MIN);
        foreground.setSound(null, Notification.AUDIO_ATTRIBUTES_DEFAULT);
        nm.createNotificationChannel(foreground);

        NotificationChannel notify = new NotificationChannel("notify", getString(R.string.channel_notify),
                NotificationManager.IMPORTANCE_DEFAULT);
        notify.setSound(null, Notification.AUDIO_ATTRIBUTES_DEFAULT);
        notify.setBypassDnd(true);
        nm.createNotificationChannel(notify);

        NotificationChannel access = new NotificationChannel("access", getString(R.string.channel_access),
                NotificationManager.IMPORTANCE_DEFAULT);
        access.setSound(null, Notification.AUDIO_ATTRIBUTES_DEFAULT);
        notify.setBypassDnd(true);
        nm.createNotificationChannel(access);
    }
}
