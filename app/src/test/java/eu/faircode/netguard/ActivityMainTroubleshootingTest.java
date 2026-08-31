package eu.faircode.netguard;

import static org.junit.Assert.assertEquals;

import android.view.LayoutInflater;
import android.view.View;

import net.kollnig.missioncontrol.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class ActivityMainTroubleshootingTest {
    private View inflateTroubleshooting() {
        return LayoutInflater.from(RuntimeEnvironment.getApplication())
                .inflate(R.layout.troubleshooting, null, false);
    }

    @Test
    public void localNetworkAccessIsHiddenBelowEnforcement() {
        View root = inflateTroubleshooting();

        ActivityMain.renderLocalNetworkAccess(root, false, false);

        assertEquals(View.GONE, root.findViewById(R.id.localNetworkAccessSection).getVisibility());
    }

    @Test
    public void localNetworkAccessShowsActionWhenPermissionIsMissing() {
        View root = inflateTroubleshooting();

        ActivityMain.renderLocalNetworkAccess(root, true, false);

        assertEquals(View.VISIBLE, root.findViewById(R.id.localNetworkAccessSection).getVisibility());
        assertEquals(View.VISIBLE, root.findViewById(R.id.btnLocalNetworkAccess).getVisibility());
        assertEquals(View.GONE, root.findViewById(R.id.tvLocalNetworkAccessStatus).getVisibility());
    }

    @Test
    public void localNetworkAccessShowsStatusWhenPermissionIsGranted() {
        View root = inflateTroubleshooting();

        ActivityMain.renderLocalNetworkAccess(root, true, true);

        assertEquals(View.VISIBLE, root.findViewById(R.id.localNetworkAccessSection).getVisibility());
        assertEquals(View.GONE, root.findViewById(R.id.btnLocalNetworkAccess).getVisibility());
        assertEquals(View.VISIBLE, root.findViewById(R.id.tvLocalNetworkAccessStatus).getVisibility());
    }
}
