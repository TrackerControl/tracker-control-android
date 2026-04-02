package eu.faircode.netguard;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Build;
import android.os.Process;
import android.provider.Settings;
import android.util.Log;

import androidx.preference.PreferenceManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Diagnostic utility for debugging work profile (Shelter) and VPN issues.
 * Collects system state, preferences, and a ring buffer of runtime events
 * that can be exported to a text file for analysis.
 */
public class WorkProfileDiagnostics {
    private static final String TAG = "TC-Diag";
    private static final int MAX_LOG_ENTRIES = 2000;

    private static WorkProfileDiagnostics instance;
    private final List<String> logBuffer = Collections.synchronizedList(new ArrayList<>());

    public static synchronized WorkProfileDiagnostics getInstance() {
        if (instance == null)
            instance = new WorkProfileDiagnostics();
        return instance;
    }

    /**
     * Append a timestamped entry to the in-memory diagnostic log.
     * Also writes to Android logcat under tag TC-Diag.
     */
    public void log(String component, String message) {
        String ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        String entry = ts + " [" + component + "] " + message;
        Log.i(TAG, entry);
        synchronized (logBuffer) {
            logBuffer.add(entry);
            while (logBuffer.size() > MAX_LOG_ENTRIES)
                logBuffer.remove(0);
        }
    }

    /**
     * Write a full diagnostic report to the given OutputStream.
     * Includes system info, preferences, VPN state, and the event log.
     */
    public void exportDiagnostics(Context context, OutputStream out) throws IOException {
        PrintWriter pw = new PrintWriter(new OutputStreamWriter(out, "UTF-8"), true);

        pw.println("=== TrackerControl Work Profile Diagnostics ===");
        pw.println("Generated: " + new Date());
        pw.println();

        // Section 1: System & profile info
        writeSystemInfo(context, pw);

        // Section 2: VPN & network state
        writeNetworkInfo(context, pw);

        // Section 3: Key preferences
        writePreferences(context, pw);

        // Section 4: Per-app SharedPreferences (apply, tracker_protect, vpn_exclude)
        writeAppPreferences(context, pw);

        // Section 5: VPN-excluded apps
        writeVpnExcludedApps(context, pw);

        // Section 6: Diagnostic event log
        writeDiagnosticLog(pw);

        // Section 7: Logcat (TC tags)
        writeLogcat(pw);

        pw.flush();
    }

    private void writeSystemInfo(Context context, PrintWriter pw) {
        pw.println("--- System Info ---");
        pw.println("Android version: " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
        pw.println("Device: " + Build.MANUFACTURER + " " + Build.MODEL);
        pw.println("App version: " + Util.getSelfVersionName(context) + " (" + Util.getSelfVersionCode(context) + ")");
        pw.println("Build flavor: " + BuildConfig.FLAVOR);
        pw.println("Debuggable: " + Util.isDebuggable(context));

        // Work profile detection
        int uid = Process.myUid();
        int userId = uid / 100000;
        pw.println("UID: " + uid);
        pw.println("User ID (profile): " + userId);
        pw.println("Is work profile: " + (userId != 0));
        pw.println("Package: " + context.getPackageName());

        // Data directory
        pw.println("Data dir: " + context.getFilesDir().getAbsolutePath());
        pw.println("DB path: " + context.getDatabasePath("Netguard.db"));

        // Memory
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            pw.println("Available RAM: " + (mi.availMem / 1024 / 1024) + " MB");
            pw.println("Low memory: " + mi.lowMemory);
        }

        pw.println();
    }

    private void writeNetworkInfo(Context context, PrintWriter pw) {
        pw.println("--- Network & DNS Info ---");

        // Private DNS setting
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                String privateDnsMode = Settings.Global.getString(
                        context.getContentResolver(), "private_dns_mode");
                String privateDnsSpecifier = Settings.Global.getString(
                        context.getContentResolver(), "private_dns_specifier");
                pw.println("Private DNS mode: " + privateDnsMode);
                pw.println("Private DNS specifier: " + privateDnsSpecifier);
            } catch (Exception e) {
                pw.println("Private DNS: unable to read (" + e.getMessage() + ")");
            }
        }

        // Active network info
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    Network active = cm.getActiveNetwork();
                    pw.println("Active network: " + active);

                    if (active != null) {
                        LinkProperties lp = cm.getLinkProperties(active);
                        if (lp != null) {
                            pw.println("Interface: " + lp.getInterfaceName());
                            pw.println("Domains: " + lp.getDomains());
                            pw.println("DNS servers: " + lp.getDnsServers());
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                pw.println("Private DNS active: " + lp.isPrivateDnsActive());
                                pw.println("Private DNS server: " + lp.getPrivateDnsServerName());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                pw.println("Network info error: " + e.getMessage());
            }
        }

        // VPN DNS servers that TC would use
        try {
            List<InetAddress> dnsServers = ServiceSinkhole.getDns(context);
            pw.println("TC DNS servers: " + dnsServers);
        } catch (Exception e) {
            pw.println("TC DNS servers: error (" + e.getMessage() + ")");
        }

        pw.println();
    }

    private void writePreferences(Context context, PrintWriter pw) {
        pw.println("--- Key Preferences ---");
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        String[] keys = {
                "enabled", "initialized", "filter", "filter_udp", "log", "log_app",
                "log_logcat", "manage_system", "include_system_vpn",
                "block_dot", "doh_enabled", "doh_endpoint", "doh_dns_fallback",
                "blocking_mode", "domain_based_blocking", "sni_enabled",
                "subnet", "lan", "tethering", "ip6", "handover",
                "loglevel", "version", "pcap",
                "vpn4", "vpn6", "dns", "dns2", "rcode",
                "whitelist_wifi", "whitelist_other",
                "screen_wifi", "screen_other",
                "onboarding_version"
        };

        for (String key : keys) {
            if (prefs.contains(key)) {
                Object val = prefs.getAll().get(key);
                pw.println("  " + key + " = " + val);
            } else {
                pw.println("  " + key + " = <not set>");
            }
        }
        pw.println();
    }

    private void writeAppPreferences(Context context, PrintWriter pw) {
        pw.println("--- Per-App SharedPreferences ---");

        String[] prefNames = {"apply", "tracker_protect", "vpn_exclude"};
        for (String name : prefNames) {
            SharedPreferences sp = context.getSharedPreferences(name, Context.MODE_PRIVATE);
            Map<String, ?> all = sp.getAll();
            pw.println("  [" + name + "] (" + all.size() + " entries):");
            if (all.isEmpty()) {
                pw.println("    (empty)");
            } else {
                // Show all entries - important for diagnosing exclusion issues
                for (Map.Entry<String, ?> entry : all.entrySet()) {
                    pw.println("    " + entry.getKey() + " = " + entry.getValue());
                }
            }
        }
        pw.println();
    }

    private void writeVpnExcludedApps(Context context, PrintWriter pw) {
        pw.println("--- VPN Routing Summary ---");
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            boolean filter = prefs.getBoolean("filter", true);
            boolean includeSystem = prefs.getBoolean("include_system_vpn", false);

            pw.println("Filter enabled: " + filter);
            pw.println("Include system apps in VPN: " + includeSystem);

            if (filter) {
                List<Rule> rules = Rule.getRules(true, context);
                int excluded = 0;
                int included = 0;
                for (Rule rule : rules) {
                    if (!rule.apply || (!includeSystem && rule.system)) {
                        pw.println("  EXCLUDED: " + rule.packageName
                                + " (apply=" + rule.apply
                                + ", system=" + rule.system + ")");
                        excluded++;
                    } else {
                        included++;
                    }
                }
                pw.println("Total: " + included + " routed through VPN, " + excluded + " excluded");
            }
        } catch (Exception e) {
            pw.println("Error listing VPN apps: " + e.getMessage());
        }
        pw.println();
    }

    private void writeDiagnosticLog(PrintWriter pw) {
        pw.println("--- Diagnostic Event Log (" + logBuffer.size() + " entries) ---");
        synchronized (logBuffer) {
            for (String entry : logBuffer) {
                pw.println(entry);
            }
        }
        pw.println();
    }

    private void writeLogcat(PrintWriter pw) {
        pw.println("--- Logcat (TrackerControl tags, last 1000 lines) ---");
        BufferedReader reader = null;
        try {
            // Capture logcat for TC-related tags
            java.lang.Process process = Runtime.getRuntime().exec(new String[]{
                    "logcat", "-d", "-t", "1000", "-v", "time",
                    "TC-Diag:*",
                    "TC-Log:*",
                    "TrackerControl.Service:*",
                    "TrackerControl.Receiver:*",
                    "TrackerControl.Settings:*",
                    "NetGuard.Service:*",
                    "*:S"  // silence everything else
            });
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                pw.println(line);
            }
            process.waitFor();
        } catch (Exception e) {
            pw.println("Logcat capture failed: " + e.getMessage());
        } finally {
            if (reader != null)
                try { reader.close(); } catch (IOException ignored) {}
        }

        // Also capture broad logcat for our PID (catches native code logs)
        pw.println();
        pw.println("--- Logcat (own PID " + Process.myPid() + ", last 500 lines) ---");
        reader = null;
        try {
            java.lang.Process process = Runtime.getRuntime().exec(new String[]{
                    "logcat", "-d", "-t", "500", "-v", "time",
                    "--pid=" + Process.myPid()
            });
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                pw.println(line);
            }
            process.waitFor();
        } catch (Exception e) {
            pw.println("PID logcat capture failed: " + e.getMessage());
        } finally {
            if (reader != null)
                try { reader.close(); } catch (IOException ignored) {}
        }

        pw.println();
        pw.println("=== End of Diagnostics ===");
    }
}
