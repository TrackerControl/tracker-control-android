package eu.faircode.netguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import net.kollnig.missioncontrol.R;

public class ReceiverShortcut extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        String nextAction = null;
        String message = null;

        if ("eu.faircode.netguard.SHORTCUT_ON".equals(action)) {
            nextAction = WidgetAdmin.INTENT_ON;
            message = context.getString(R.string.shortcut_on);
        } else if ("eu.faircode.netguard.SHORTCUT_OFF".equals(action)) {
            nextAction = WidgetAdmin.INTENT_OFF;
            message = context.getString(R.string.shortcut_off);
        } else {
            // Fallback to toggle if needed
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            boolean enabled = prefs.getBoolean("enabled", false);
            nextAction = (enabled ? WidgetAdmin.INTENT_OFF : WidgetAdmin.INTENT_ON);
            message = context.getString(enabled ? R.string.shortcut_off : R.string.shortcut_on);
        }

        if (nextAction != null) {
            Intent toggleIntent = new Intent(nextAction);
            toggleIntent.setPackage(context.getPackageName());
            context.sendBroadcast(toggleIntent);

            if (message != null) {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
            }
        }
    }
}