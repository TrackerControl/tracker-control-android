package eu.faircode.netguard;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.TextView;
import androidx.preference.PreferenceManager;
import net.kollnig.missioncontrol.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.File;
import java.io.FileOutputStream;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w320dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ActivityMainResearchNoticeTest {
    @Test
    public void noticeFollowsResearchPreferenceOutsideTabContentAndOpensSettings() throws Exception {
        ActivityMain activity = Robolectric.buildActivity(ActivityMain.class).get();
        activity.setTheme(R.style.AppTheme_NoActionBar);
        activity.getDelegate().installViewFactory();
        activity.setContentView(R.layout.main);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        // SNI alone does not disable tracker blocking or mean Research is on.
        prefs.edit().putBoolean("log_logcat", false).putBoolean("sni_enabled", true).commit();
        activity.onSharedPreferenceChanged(prefs, "log_logcat");
        TextView notice = activity.findViewById(R.id.tvResearchMode);
        assertEquals(View.GONE, notice.getVisibility());

        // Imported settings can enable Research without SNI. Still warn.
        prefs.edit().putBoolean("log_logcat", true).putBoolean("sni_enabled", false).commit();
        activity.onSharedPreferenceChanged(prefs, "log_logcat");
        activity.findViewById(R.id.llApps).setVisibility(View.GONE);
        assertEquals(View.VISIBLE, notice.getVisibility());
        assertSame(activity.findViewById(R.id.tvPrivateDns).getParent(), notice.getParent());
        assertTrue(notice.getText().toString().contains("Tracker blocking is disabled"));
        notice.performClick();
        assertEquals(ActivitySettings.class.getName(),
                shadowOf(activity).getNextStartedActivity().getComponent().getClassName());

        int width = Math.round(320 * activity.getResources().getDisplayMetrics().density);
        View root = (View) notice.getParent();
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(width * 2, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, width * 2);
        assertEquals(0, notice.getLayout().getEllipsisCount(notice.getLineCount() - 1));
        Bitmap bitmap = Bitmap.createBitmap(width, notice.getHeight(), Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(android.graphics.Color.WHITE);
        notice.draw(new Canvas(bitmap));
        File output = new File("build/reports/research-mode-notice.png");
        output.getParentFile().mkdirs();
        try (FileOutputStream stream = new FileOutputStream(output)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        }

        prefs.edit().putBoolean("log_logcat", false).putBoolean("sni_enabled", true).commit();
        activity.onSharedPreferenceChanged(prefs, "log_logcat");
        assertEquals(View.GONE, notice.getVisibility());
    }
}
