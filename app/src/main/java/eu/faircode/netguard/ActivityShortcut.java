package eu.faircode.netguard;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import net.kollnig.missioncontrol.R;

public class ActivityShortcut extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String action = getIntent().getAction();
        String nextAction = null;
        String message = null;

        if ("eu.faircode.netguard.SHORTCUT_ON".equals(action)) {
            nextAction = WidgetAdmin.INTENT_ON;
            message = getString(R.string.shortcut_on);
        } else if ("eu.faircode.netguard.SHORTCUT_OFF".equals(action)) {
            nextAction = WidgetAdmin.INTENT_OFF;
            message = getString(R.string.shortcut_off);
        }

        if (nextAction != null) {
            Intent toggleIntent = new Intent(nextAction);
            toggleIntent.setPackage(getPackageName());
            sendBroadcast(toggleIntent);
            
            if (message != null) {
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            }
        }

        finish();
    }
}