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

import static net.kollnig.missioncontrol.data.TrackerBlocklist.PREF_BLOCKLIST;

import android.Manifest;
import android.annotation.TargetApi;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.preference.EditTextPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;
import android.preference.TwoStatePreference;
import android.text.TextUtils;
import android.util.Log;
import android.util.Xml;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NavUtils;
import androidx.core.util.PatternsCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;

import net.kollnig.missioncontrol.BuildConfig;
import net.kollnig.missioncontrol.R;
import net.kollnig.missioncontrol.data.InternetBlocklist;
import net.kollnig.missioncontrol.data.TrackerBlocklist;
import net.kollnig.missioncontrol.data.TrackerList;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

public class ActivitySettings extends AppCompatActivity implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = "TrackerControl.Settings";

    private boolean running = false;

    private static final int REQUEST_EXPORT = 1;
    private static final int REQUEST_IMPORT = 2;
    private static final int REQUEST_HOSTS = 3;
    private static final int REQUEST_HOSTS_APPEND = 4;
    private static final int REQUEST_CALL = 5;

    private AlertDialog dialogFilter = null;

    private static final Intent INTENT_VPN_SETTINGS = new Intent("android.net.vpn.SETTINGS");

    protected void onCreate(Bundle savedInstanceState) {
        Util.setTheme(this);
        super.onCreate(savedInstanceState);
        getFragmentManager().beginTransaction().replace(android.R.id.content, new FragmentSettings()).commit();
        getSupportActionBar().setTitle(R.string.menu_settings);
        running = true;
    }

    private PreferenceScreen getPreferenceScreen() {
        return ((PreferenceFragment) getFragmentManager().findFragmentById(android.R.id.content)).getPreferenceScreen();
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        final PreferenceScreen screen = getPreferenceScreen();
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        PreferenceGroup cat_options = (PreferenceGroup) ((PreferenceGroup) screen.findPreference("screen_options")).findPreference("category_options");
        PreferenceGroup cat_network = (PreferenceGroup) ((PreferenceGroup) screen.findPreference("screen_network_options")).findPreference("category_network_options");
        PreferenceGroup cat_advanced = (PreferenceGroup) ((PreferenceGroup) screen.findPreference("screen_advanced_options")).findPreference("category_advanced_options");
        PreferenceGroup cat_backup = (PreferenceGroup) ((PreferenceGroup) screen.findPreference("screen_backup")).findPreference("category_backup");

        // Handle pause
        Preference pref_pause = screen.findPreference("pause");
        pref_pause.setTitle(getString(R.string.setting_pause, prefs.getString("pause", "10")));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            TwoStatePreference pref_handover =
                    (TwoStatePreference) screen.findPreference("handover");
            cat_advanced.removePreference(pref_handover);
        }

        Preference pref_reset_usage = screen.findPreference("reset_usage");
        pref_reset_usage.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                Util.areYouSure(ActivitySettings.this, R.string.setting_reset_usage, new Util.DoubtListener() {
                    @Override
                    public void onSure() {
                        new AsyncTask<Object, Object, Throwable>() {
                            @Override
                            protected Throwable doInBackground(Object... objects) {
                                try {
                                    DatabaseHelper.getInstance(ActivitySettings.this).resetUsage(-1);
                                    return null;
                                } catch (Throwable ex) {
                                    return ex;
                                }
                            }

                            @Override
                            protected void onPostExecute(Throwable ex) {
                                if (ex == null)
                                    Toast.makeText(ActivitySettings.this, R.string.msg_completed, Toast.LENGTH_LONG).show();
                                else
                                    Toast.makeText(ActivitySettings.this, ex.toString(), Toast.LENGTH_LONG).show();
                            }
                        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                    }
                });
                return false;
            }
        });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            TwoStatePreference pref_reload_onconnectivity =
                    (TwoStatePreference) screen.findPreference("reload_onconnectivity");
            pref_reload_onconnectivity.setChecked(true);
            pref_reload_onconnectivity.setEnabled(false);
        }

        // Handle port forwarding
        Preference pref_forwarding = screen.findPreference("forwarding");
        pref_forwarding.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                startActivity(new Intent(ActivitySettings.this, ActivityForwarding.class));
                return true;
            }
        });

        // VPN parameters
        screen.findPreference("vpn4").setTitle(getString(R.string.setting_vpn4, prefs.getString("vpn4", "10.1.10.1")));
        screen.findPreference("vpn6").setTitle(getString(R.string.setting_vpn6, prefs.getString("vpn6", "fd00:1:fd00:1:fd00:1:fd00:1")));
        EditTextPreference pref_dns1 = (EditTextPreference) screen.findPreference("dns");
        EditTextPreference pref_dns2 = (EditTextPreference) screen.findPreference("dns2");
        EditTextPreference pref_validate = (EditTextPreference) screen.findPreference("validate");
        EditTextPreference pref_ttl = (EditTextPreference) screen.findPreference("ttl");
        pref_dns1.setTitle(getString(R.string.setting_dns, prefs.getString("dns", "-")));
        pref_dns2.setTitle(getString(R.string.setting_dns, prefs.getString("dns2", "-")));
        pref_validate.setTitle(getString(R.string.setting_validate, prefs.getString("validate", "www.f-droid.org")));
        pref_ttl.setTitle(getString(R.string.setting_ttl, prefs.getString("ttl", "259200")));

        // SOCKS5 parameters
        screen.findPreference("socks5_addr").setTitle(getString(R.string.setting_socks5_addr, prefs.getString("socks5_addr", "-")));
        screen.findPreference("socks5_port").setTitle(getString(R.string.setting_socks5_port, prefs.getString("socks5_port", "-")));
        screen.findPreference("socks5_username").setTitle(getString(R.string.setting_socks5_username, prefs.getString("socks5_username", "-")));
        screen.findPreference("socks5_password").setTitle(getString(R.string.setting_socks5_password, TextUtils.isEmpty(prefs.getString("socks5_username", "")) ? "-" : "*****"));

        // PCAP parameters
        screen.findPreference("pcap_record_size").setTitle(getString(R.string.setting_pcap_record_size, prefs.getString("pcap_record_size", "64")));
        screen.findPreference("pcap_file_size").setTitle(getString(R.string.setting_pcap_file_size, prefs.getString("pcap_file_size", "2")));

        // Watchdog
        screen.findPreference("watchdog").setTitle(getString(R.string.setting_watchdog, prefs.getString("watchdog", "0")));

        // Show resolved
        Preference pref_show_resolved = screen.findPreference("show_resolved");
        pref_show_resolved.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                startActivity(new Intent(ActivitySettings.this, ActivityDns.class));
                return true;
            }
        });

        // Handle export
        Preference pref_export = screen.findPreference("export");
        pref_export.setEnabled(getIntentCreateExport().resolveActivity(getPackageManager()) != null);
        pref_export.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                startActivityForResult(getIntentCreateExport(), ActivitySettings.REQUEST_EXPORT);
                return true;
            }
        });

        // Handle import
        Preference pref_import = screen.findPreference("import");
        pref_import.setEnabled(getIntentOpenExport().resolveActivity(getPackageManager()) != null);
        pref_import.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                startActivityForResult(getIntentOpenExport(), ActivitySettings.REQUEST_IMPORT);
                return true;
            }
        });

        // Hosts file settings
        cat_advanced.removePreference(screen.findPreference("use_hosts"));
        EditTextPreference pref_rcode = (EditTextPreference) screen.findPreference("rcode");
        Preference pref_hosts_import = screen.findPreference("hosts_import");
        Preference pref_hosts_import_append = screen.findPreference("hosts_import_append");
        EditTextPreference pref_hosts_url = (EditTextPreference) screen.findPreference("hosts_url_new");
        final Preference pref_hosts_download = screen.findPreference("hosts_download");

        pref_rcode.setTitle(getString(R.string.setting_rcode, prefs.getString("rcode", "3")));
        cat_advanced.removePreference(pref_rcode);

        if (Util.isFDroidInstall()
                || Util.isPlayStoreInstall(this))
            cat_options.removePreference(screen.findPreference("update_check"));

        if (Util.isPlayStoreInstall())
            cat_options.removePreference(screen.findPreference("strict_blocking"));

        if (Util.isPlayStoreInstall(this)) {
            Log.i(TAG, "Play store install");
            cat_advanced.removePreference(pref_rcode);
            cat_advanced.removePreference(pref_forwarding);
            cat_advanced.removePreference(pref_hosts_import);
            cat_advanced.removePreference(pref_hosts_import_append);
            cat_advanced.removePreference(pref_hosts_url);
            cat_advanced.removePreference(pref_hosts_download);

        } else {
            String last_import = prefs.getString("hosts_last_import", null);
            String last_download = prefs.getString("hosts_last_download", null);
            if (last_import != null)
                pref_hosts_import.setSummary(getString(R.string.msg_import_last, last_import));
            if (last_download != null)
                pref_hosts_download.setSummary(getString(R.string.msg_update_last, last_download));

            // Handle hosts import
            // https://github.com/Free-Software-for-Android/AdAway/wiki/HostsSources
            pref_hosts_import.setEnabled(getIntentOpenHosts().resolveActivity(getPackageManager()) != null);
            pref_hosts_import.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    startActivityForResult(getIntentOpenHosts(), ActivitySettings.REQUEST_HOSTS);
                    return true;
                }
            });
            pref_hosts_import_append.setEnabled(pref_hosts_import.isEnabled());
            pref_hosts_import_append.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    startActivityForResult(getIntentOpenHosts(), ActivitySettings.REQUEST_HOSTS_APPEND);
                    return true;
                }
            });

            // Handle hosts file download
            pref_hosts_url.setSummary(pref_hosts_url.getText());
            pref_hosts_download.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    final File tmp = new File(getFilesDir(), "hosts.tmp");
                    final File hosts = new File(getFilesDir(), "hosts.txt");
                    EditTextPreference pref_hosts_url = (EditTextPreference) screen.findPreference("hosts_url_new");
                    try {
                        new DownloadTask(ActivitySettings.this, new URL(pref_hosts_url.getText()), tmp, new DownloadTask.Listener() {
                            @Override
                            public void onCompleted() {
                                if (hosts.exists())
                                    hosts.delete();
                                tmp.renameTo(hosts);

                                String last = SimpleDateFormat.getDateTimeInstance().format(new Date().getTime());
                                prefs.edit().putString("hosts_last_download", last).apply();

                                if (running) {
                                    pref_hosts_download.setSummary(getString(R.string.msg_update_last, last));
                                    Toast.makeText(ActivitySettings.this, R.string.msg_updated, Toast.LENGTH_LONG).show();
                                }

                                ServiceSinkhole.reload("hosts file download", ActivitySettings.this, false);
                            }

                            @Override
                            public void onCancelled() {
                                if (tmp.exists())
                                    tmp.delete();
                            }

                            @Override
                            public void onException(Throwable ex) {
                                if (tmp.exists())
                                    tmp.delete();

                                if (running)
                                    Toast.makeText(ActivitySettings.this, ex.getMessage(), Toast.LENGTH_LONG).show();
                            }
                        }).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                    } catch (MalformedURLException ex) {
                        Toast.makeText(ActivitySettings.this, ex.toString(), Toast.LENGTH_LONG).show();
                    }
                    return true;
                }
            });
        }

        // Development
        if (!Util.isDebuggable(this))
            screen.removePreference(screen.findPreference("screen_development"));

        /*cat_network.removePreference(screen.findPreference("use_metered"));
        cat_network.removePreference(screen.findPreference("unmetered_2g"));
        cat_network.removePreference(screen.findPreference("unmetered_3g"));
        cat_network.removePreference(screen.findPreference("unmetered_4g"));
        cat_network.removePreference(screen.findPreference("national_roaming"));
        cat_network.removePreference(screen.findPreference("eu_roaming"));
        cat_network.removePreference(screen.findPreference("lockdown_wifi"));
        cat_network.removePreference(screen.findPreference("lockdown_other"));
        cat_network.removePreference(screen.findPreference("reload_onconnectivity"));*/
    }

    @Override
    protected void onResume() {
        super.onResume();

        checkPermissions(null);

        // Listen for preference changes
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    protected void onPause() {
        super.onPause();

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    protected void onDestroy() {
        running = false;
        if (dialogFilter != null) {
            dialogFilter.dismiss();
            dialogFilter = null;
        }
        super.onDestroy();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                Log.i(TAG, "Up");
                NavUtils.navigateUpFromSameTask(this);
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    @TargetApi(Build.VERSION_CODES.M)
    public void onSharedPreferenceChanged(SharedPreferences prefs, String name) {
        if ("show_stats".equals(name)) {
            ((TwoStatePreference) getPreferenceScreen().findPreference(name)).setChecked(prefs.getBoolean(name, false));
        }

        Object value = prefs.getAll().get(name);
        if (value instanceof String && "".equals(value))
            prefs.edit().remove(name).apply();

        // Dependencies
        if ("screen_on".equals(name))
            ServiceSinkhole.reload("changed " + name, this, false);

        else if ("whitelist_wifi".equals(name) ||
                "screen_wifi".equals(name))
            ServiceSinkhole.reload("changed " + name, this, false);

        else if ("whitelist_other".equals(name) ||
                "screen_other".equals(name))
            ServiceSinkhole.reload("changed " + name, this, false);

        else if ("whitelist_roaming".equals(name))
            ServiceSinkhole.reload("changed " + name, this, false);

        else if ("pause".equals(name))
            getPreferenceScreen().findPreference(name).setTitle(getString(R.string.setting_pause, prefs.getString(name, "10")));

        else if ("dark_theme".equals(name))
            recreate();

        else if ("subnet".equals(name))
            ServiceSinkhole.reload("changed " + name, this, false);

        else if ("tethering".equals(name))
            ServiceSinkhole.reload("changed " + name, this, false);

        else if ("lan".equals(name))
            ServiceSinkhole.reload("changed " + name, this, false);

        else if ("ip6".equals(name))
            ServiceSinkhole.reload("changed " + name, this, false);

        else if ("use_metered".equals(name))
            ServiceSinkhole.reload("changed " + name, this, false);

        else if ("unmetered_2g".equals(name) ||
                "unmetered_3g".equals(name) ||
                "unmetered_4g".equals(name))
            ServiceSinkhole.reload("changed " + name, this, false);

        else if ("national_roaming".equals(name))
            ServiceSinkhole.reload("changed " + name, this, false);

        else if ("eu_roaming".equals(name))
            ServiceSinkhole.reload("changed " + name, this, false);

        else if ("disable_on_call".equals(name)) {
            if (prefs.getBoolean(name, false)) {
                if (checkPermissions(name))
                    ServiceSinkhole.reload("changed " + name, this, false);
            } else
                ServiceSinkhole.reload("changed " + name, this, false);

        } else if ("lockdown_wifi".equals(name) || "lockdown_other".equals(name))
            ServiceSinkhole.reload("changed " + name, this, false);

        else if ("manage_system".equals(name)) {
            boolean manage = prefs.getBoolean(name, false);
            if (!manage)
                prefs.edit().putBoolean("show_user", true).apply();
            prefs.edit().putBoolean("show_system", manage).apply();
            ServiceSinkhole.reload("changed " + name, this, false);

        } else if ("log_app".equals(name)) {
            Intent ruleset = new Intent(ActivityMain.ACTION_RULES_CHANGED);
            LocalBroadcastManager.getInstance(this).sendBroadcast(ruleset);
            ServiceSinkhole.reload("changed " + name, this, false);

        } else if ("notify_access".equals(name))
            ServiceSinkhole.reload("changed " + name, this, false);

        else if ("filter".equals(name)) {
            // Show dialog
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && prefs.getBoolean(name, true)) {
                LayoutInflater inflater = LayoutInflater.from(ActivitySettings.this);
                View view = inflater.inflate(R.layout.filter, null, false);
                dialogFilter = new AlertDialog.Builder(ActivitySettings.this)
                        .setView(view)
                        .setCancelable(false)
                        .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                // Do nothing
                            }
                        })
                        .setOnDismissListener(new DialogInterface.OnDismissListener() {
                            @Override
                            public void onDismiss(DialogInterface dialogInterface) {
                                dialogFilter = null;
                            }
                        })
                        .create();
                dialogFilter.show();
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP && !prefs.getBoolean(name, false)) {
                prefs.edit().putBoolean(name, true).apply();
                Toast.makeText(ActivitySettings.this, R.string.msg_filter4, Toast.LENGTH_SHORT).show();
            }

            ((TwoStatePreference) getPreferenceScreen().findPreference(name)).setChecked(prefs.getBoolean(name, false));

            ServiceSinkhole.reload("changed " + name, this, false);

        } else if ("use_hosts".equals(name))
            ServiceSinkhole.reload("changed " + name, this, false);

        else if ("vpn4".equals(name)) {
            String vpn4 = prefs.getString(name, null);
            try {
                checkAddress(vpn4, false);
                prefs.edit().putString(name, vpn4.trim()).apply();
            } catch (Throwable ex) {
                prefs.edit().remove(name).apply();
                ((EditTextPreference) getPreferenceScreen().findPreference(name)).setText(null);
                if (!TextUtils.isEmpty(vpn4))
                    Toast.makeText(ActivitySettings.this, ex.toString(), Toast.LENGTH_LONG).show();
            }
            getPreferenceScreen().findPreference(name).setTitle(
                    getString(R.string.setting_vpn4, prefs.getString(name, "10.1.10.1")));
            ServiceSinkhole.reload("changed " + name, this, false);

        } else if ("vpn6".equals(name)) {
            String vpn6 = prefs.getString(name, null);
            try {
                checkAddress(vpn6, false);
                prefs.edit().putString(name, vpn6.trim()).apply();
            } catch (Throwable ex) {
                prefs.edit().remove(name).apply();
                ((EditTextPreference) getPreferenceScreen().findPreference(name)).setText(null);
                if (!TextUtils.isEmpty(vpn6))
                    Toast.makeText(ActivitySettings.this, ex.toString(), Toast.LENGTH_LONG).show();
            }
            getPreferenceScreen().findPreference(name).setTitle(
                    getString(R.string.setting_vpn6, prefs.getString(name, "fd00:1:fd00:1:fd00:1:fd00:1")));
            ServiceSinkhole.reload("changed " + name, this, false);

        } else if ("dns".equals(name) || "dns2".equals(name)) {
            String dns = prefs.getString(name, null);
            try {
                checkAddress(dns, true);
                prefs.edit().putString(name, dns.trim()).apply();
            } catch (Throwable ex) {
                prefs.edit().remove(name).apply();
                ((EditTextPreference) getPreferenceScreen().findPreference(name)).setText(null);
                if (!TextUtils.isEmpty(dns))
                    Toast.makeText(ActivitySettings.this, ex.toString(), Toast.LENGTH_LONG).show();
            }
            getPreferenceScreen().findPreference(name).setTitle(
                    getString(R.string.setting_dns, prefs.getString(name, "-")));
            ServiceSinkhole.reload("changed " + name, this, false);

        } else if ("validate".equals(name)) {
            String host = prefs.getString(name, "www.f-droid.org");
            try {
                checkDomain(host);
                prefs.edit().putString(name, host.trim()).apply();
            } catch (Throwable ex) {
                prefs.edit().remove(name).apply();
                ((EditTextPreference) getPreferenceScreen().findPreference(name)).setText(null);
                if (!TextUtils.isEmpty(host))
                    Toast.makeText(ActivitySettings.this, ex.toString(), Toast.LENGTH_LONG).show();
            }
            getPreferenceScreen().findPreference(name).setTitle(
                    getString(R.string.setting_validate, prefs.getString(name, "www.f-droid.org")));
            ServiceSinkhole.reload("changed " + name, this, false);

        } else if ("ttl".equals(name))
            getPreferenceScreen().findPreference(name).setTitle(
                    getString(R.string.setting_ttl, prefs.getString(name, "259200")));

        else if ("rcode".equals(name)) {
            getPreferenceScreen().findPreference(name).setTitle(
                    getString(R.string.setting_rcode, prefs.getString(name, "3")));
            ServiceSinkhole.reload("changed " + name, this, false);

        } else if ("socks5_enabled".equals(name))
            ServiceSinkhole.reload("changed " + name, this, false);

        else if ("socks5_addr".equals(name)) {
            String socks5_addr = prefs.getString(name, null);
            try {
                if (!TextUtils.isEmpty(socks5_addr) && !Util.isNumericAddress(socks5_addr))
                    throw new IllegalArgumentException("Bad address");
            } catch (Throwable ex) {
                prefs.edit().remove(name).apply();
                ((EditTextPreference) getPreferenceScreen().findPreference(name)).setText(null);
                if (!TextUtils.isEmpty(socks5_addr))
                    Toast.makeText(ActivitySettings.this, ex.toString(), Toast.LENGTH_LONG).show();
            }
            getPreferenceScreen().findPreference(name).setTitle(
                    getString(R.string.setting_socks5_addr, prefs.getString(name, "-")));
            ServiceSinkhole.reload("changed " + name, this, false);

        } else if ("socks5_port".equals(name)) {
            getPreferenceScreen().findPreference(name).setTitle(getString(R.string.setting_socks5_port, prefs.getString(name, "-")));
            ServiceSinkhole.reload("changed " + name, this, false);

        } else if ("socks5_username".equals(name)) {
            getPreferenceScreen().findPreference(name).setTitle(getString(R.string.setting_socks5_username, prefs.getString(name, "-")));
            ServiceSinkhole.reload("changed " + name, this, false);

        } else if ("socks5_password".equals(name)) {
            getPreferenceScreen().findPreference(name).setTitle(getString(R.string.setting_socks5_password, TextUtils.isEmpty(prefs.getString(name, "")) ? "-" : "*****"));
            ServiceSinkhole.reload("changed " + name, this, false);

        } else if ("pcap_record_size".equals(name) || "pcap_file_size".equals(name)) {
            if ("pcap_record_size".equals(name))
                getPreferenceScreen().findPreference(name).setTitle(getString(R.string.setting_pcap_record_size, prefs.getString(name, "64")));
            else
                getPreferenceScreen().findPreference(name).setTitle(getString(R.string.setting_pcap_file_size, prefs.getString(name, "2")));

            ServiceSinkhole.setPcap(false, this);

            File pcap_file = new File(getDir("data", MODE_PRIVATE), "netguard.pcap");
            if (pcap_file.exists() && !pcap_file.delete())
                Log.w(TAG, "Delete PCAP failed");

            if (prefs.getBoolean("pcap", false))
                ServiceSinkhole.setPcap(true, this);

        } else if ("watchdog".equals(name)) {
            getPreferenceScreen().findPreference(name).setTitle(getString(R.string.setting_watchdog, prefs.getString(name, "0")));
            ServiceSinkhole.reload("changed " + name, this, false);

        } else if ("show_stats".equals(name))
            ServiceSinkhole.reloadStats("changed " + name, this);

        else if ("stats_frequency".equals(name))
            getPreferenceScreen().findPreference(name).setTitle(getString(R.string.setting_stats_frequency, prefs.getString(name, "1000")));

        else if ("stats_samples".equals(name))
            getPreferenceScreen().findPreference(name).setTitle(getString(R.string.setting_stats_samples, prefs.getString(name, "90")));

        else if ("hosts_url_new".equals(name))
            getPreferenceScreen().findPreference(name).setSummary(prefs.getString(name, BuildConfig.HOSTS_FILE_URI));

        else if ("loglevel".equals(name))
            ServiceSinkhole.reload("changed " + name, this, false);

        else if ("domain_based_blocked".equals(name)) {
            TrackerList ts = TrackerList.getInstance(this);
            ts.loadTrackers(this);
        }

    }

    @TargetApi(Build.VERSION_CODES.M)
    private boolean checkPermissions(String name) {
        PreferenceScreen screen = getPreferenceScreen();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        // Check if permission was revoked
        if ((name == null || "disable_on_call".equals(name)) && prefs.getBoolean("disable_on_call", false))
            if (!Util.hasPhoneStatePermission(this)) {
                prefs.edit().putBoolean("disable_on_call", false).apply();
                ((TwoStatePreference) screen.findPreference("disable_on_call")).setChecked(false);

                requestPermissions(new String[]{Manifest.permission.READ_PHONE_STATE}, REQUEST_CALL);

                if (name != null)
                    return false;
            }

        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        PreferenceScreen screen = getPreferenceScreen();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        boolean granted = (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED);

        if (requestCode == REQUEST_CALL) {
            prefs.edit().putBoolean("disable_on_call", granted).apply();
            ((TwoStatePreference) screen.findPreference("disable_on_call")).setChecked(granted);
        }

        if (granted)
            ServiceSinkhole.reload("permission granted", this, false);
    }

    private void checkAddress(String address, boolean allow_local) throws IllegalArgumentException, UnknownHostException {
        if (address != null)
            address = address.trim();
        if (TextUtils.isEmpty(address))
            throw new IllegalArgumentException("Bad address");
        if (!Util.isNumericAddress(address))
            throw new IllegalArgumentException("Bad address");
        if (!allow_local) {
            InetAddress iaddr = InetAddress.getByName(address);
            if (iaddr.isLoopbackAddress() || iaddr.isAnyLocalAddress())
                throw new IllegalArgumentException("Bad address");
        }
    }

    private void checkDomain(String address) throws IllegalArgumentException, UnknownHostException {
        if (address != null)
            address = address.trim();
        if (TextUtils.isEmpty(address))
            throw new IllegalArgumentException("Bad address");
        if (Util.isNumericAddress(address))
            throw new IllegalArgumentException("Bad address");
        if (!PatternsCompat.DOMAIN_NAME.matcher(address).matches())
            throw new IllegalArgumentException("Bad address");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, final Intent data) {
        Log.i(TAG, "onActivityResult request=" + requestCode + " result=" + requestCode + " ok=" + (resultCode == RESULT_OK));
        if (requestCode == REQUEST_EXPORT) {
            if (resultCode == RESULT_OK && data != null)
                handleExport(data);

        } else if (requestCode == REQUEST_IMPORT) {
            if (resultCode == RESULT_OK && data != null)
                handleImport(data);

        } else if (requestCode == REQUEST_HOSTS) {
            if (resultCode == RESULT_OK && data != null)
                handleHosts(data, false);

        } else if (requestCode == REQUEST_HOSTS_APPEND) {
            if (resultCode == RESULT_OK && data != null)
                handleHosts(data, true);

        } else {
            Log.w(TAG, "Unknown activity result request=" + requestCode);
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    private Intent getIntentCreateExport() {
        Intent intent;
        intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*"); // text/xml
        intent.putExtra(Intent.EXTRA_TITLE, "trackercontrol_" + new SimpleDateFormat("yyyyMMdd").format(new Date().getTime()) + ".xml");
        return intent;
    }

    private Intent getIntentOpenExport() {
        Intent intent;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT)
            intent = new Intent(Intent.ACTION_GET_CONTENT);
        else
            intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*"); // text/xml
        return intent;
    }

    private Intent getIntentOpenHosts() {
        Intent intent;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT)
            intent = new Intent(Intent.ACTION_GET_CONTENT);
        else
            intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*"); // text/plain
        return intent;
    }

    private void handleExport(final Intent data) {
        new AsyncTask<Object, Object, Throwable>() {
            @Override
            protected Throwable doInBackground(Object... objects) {
                OutputStream out = null;
                try {
                    Uri target = data.getData();
                    if (data.hasExtra("org.openintents.extra.DIR_PATH"))
                        target = Uri.parse(target + "/trackercontrol_" + new SimpleDateFormat("yyyyMMdd").format(new Date().getTime()) + ".xml");
                    Log.i(TAG, "Writing URI=" + target);
                    out = getContentResolver().openOutputStream(target);
                    xmlExport(out);
                    return null;
                } catch (Throwable ex) {
                    Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                    return ex;
                } finally {
                    if (out != null)
                        try {
                            out.close();
                        } catch (IOException ex) {
                            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                        }
                }
            }

            @Override
            protected void onPostExecute(Throwable ex) {
                if (running) {
                    if (ex == null)
                        Toast.makeText(ActivitySettings.this, R.string.msg_completed, Toast.LENGTH_LONG).show();
                    else
                        Toast.makeText(ActivitySettings.this, ex.toString(), Toast.LENGTH_LONG).show();
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void handleHosts(final Intent data, final boolean append) {
        new AsyncTask<Object, Object, Throwable>() {
            @Override
            protected Throwable doInBackground(Object... objects) {
                File hosts = new File(getFilesDir(), "hosts.txt");

                FileOutputStream out = null;
                InputStream in = null;
                try {
                    Log.i(TAG, "Reading URI=" + data.getData());
                    ContentResolver resolver = getContentResolver();
                    String[] streamTypes = resolver.getStreamTypes(data.getData(), "*/*");
                    String streamType = (streamTypes == null || streamTypes.length == 0 ? "*/*" : streamTypes[0]);
                    AssetFileDescriptor descriptor = resolver.openTypedAssetFileDescriptor(data.getData(), streamType, null);
                    in = descriptor.createInputStream();
                    out = new FileOutputStream(hosts, append);

                    int len;
                    long total = 0;
                    byte[] buf = new byte[4096];
                    while ((len = in.read(buf)) > 0) {
                        out.write(buf, 0, len);
                        total += len;
                    }
                    Log.i(TAG, "Copied bytes=" + total);

                    return null;
                } catch (Throwable ex) {
                    Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                    return ex;
                } finally {
                    if (out != null)
                        try {
                            out.close();
                        } catch (IOException ex) {
                            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                        }
                    if (in != null)
                        try {
                            in.close();
                        } catch (IOException ex) {
                            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                        }
                }
            }

            @Override
            protected void onPostExecute(Throwable ex) {
                if (running) {
                    if (ex == null) {
                        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ActivitySettings.this);
                        String last = SimpleDateFormat.getDateTimeInstance().format(new Date().getTime());
                        prefs.edit().putString("hosts_last_import", last).apply();

                        if (running) {
                            getPreferenceScreen().findPreference("hosts_import").setSummary(getString(R.string.msg_import_last, last));
                            Toast.makeText(ActivitySettings.this, R.string.msg_completed, Toast.LENGTH_LONG).show();
                        }

                        ServiceSinkhole.reload("hosts import", ActivitySettings.this, false);
                    } else
                        Toast.makeText(ActivitySettings.this, ex.toString(), Toast.LENGTH_LONG).show();
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void handleImport(final Intent data) {
        new AsyncTask<Object, Object, Throwable>() {
            @Override
            protected Throwable doInBackground(Object... objects) {
                InputStream in = null;
                try {
                    Log.i(TAG, "Reading URI=" + data.getData());
                    ContentResolver resolver = getContentResolver();
                    String[] streamTypes = resolver.getStreamTypes(data.getData(), "*/*");
                    String streamType = (streamTypes == null || streamTypes.length == 0 ? "*/*" : streamTypes[0]);
                    AssetFileDescriptor descriptor = resolver.openTypedAssetFileDescriptor(data.getData(), streamType, null);
                    in = descriptor.createInputStream();
                    xmlImport(in);
                    return null;
                } catch (Throwable ex) {
                    Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                    return ex;
                } finally {
                    if (in != null)
                        try {
                            in.close();
                        } catch (IOException ex) {
                            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                        }
                }
            }

            @Override
            protected void onPostExecute(Throwable ex) {
                if (running) {
                    if (ex == null) {
                        Toast.makeText(ActivitySettings.this, R.string.msg_completed, Toast.LENGTH_LONG).show();
                        ServiceSinkhole.reloadStats("import", ActivitySettings.this);
                        // Update theme, request permissions
                        recreate();
                    } else
                        Toast.makeText(ActivitySettings.this, ex.toString(), Toast.LENGTH_LONG).show();
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void xmlExport(OutputStream out) throws IOException {
        XmlSerializer serializer = Xml.newSerializer();
        serializer.setOutput(out, "UTF-8");
        serializer.startDocument(null, true);
        serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
        serializer.startTag(null, "trackercontrol");

        serializer.startTag(null, "application");
        xmlExport(PreferenceManager.getDefaultSharedPreferences(this), serializer);
        serializer.endTag(null, "application");

        serializer.startTag(null, "wifi");
        xmlExport(getSharedPreferences("wifi", Context.MODE_PRIVATE), serializer);
        serializer.endTag(null, "wifi");

        serializer.startTag(null, "mobile");
        xmlExport(getSharedPreferences("other", Context.MODE_PRIVATE), serializer);
        serializer.endTag(null, "mobile");

        serializer.startTag(null, "screen_wifi");
        xmlExport(getSharedPreferences("screen_wifi", Context.MODE_PRIVATE), serializer);
        serializer.endTag(null, "screen_wifi");

        serializer.startTag(null, "screen_other");
        xmlExport(getSharedPreferences("screen_other", Context.MODE_PRIVATE), serializer);
        serializer.endTag(null, "screen_other");

        serializer.startTag(null, "roaming");
        xmlExport(getSharedPreferences("roaming", Context.MODE_PRIVATE), serializer);
        serializer.endTag(null, "roaming");

        serializer.startTag(null, "lockdown");
        xmlExport(getSharedPreferences("lockdown", Context.MODE_PRIVATE), serializer);
        serializer.endTag(null, "lockdown");

        serializer.startTag(null, "apply");
        xmlExport(getSharedPreferences("apply", Context.MODE_PRIVATE), serializer);
        serializer.endTag(null, "apply");

        serializer.startTag(null, "notify");
        xmlExport(getSharedPreferences("notify", Context.MODE_PRIVATE), serializer);
        serializer.endTag(null, "notify");

        serializer.startTag(null, "forward");
        forwardExport(serializer);
        serializer.endTag(null, "forward");

        serializer.startTag(null, "blocklist");
        xmlExport(getSharedPreferences(PREF_BLOCKLIST, Context.MODE_PRIVATE), serializer);
        serializer.endTag(null, "blocklist");

        serializer.endTag(null, "trackercontrol");
        serializer.endDocument();
        serializer.flush();
    }

    private void xmlExport(SharedPreferences prefs, XmlSerializer serializer) throws IOException {
        Map<String, ?> settings = prefs.getAll();
        for (String key : settings.keySet()) {
            Object value = settings.get(key);

            if ("imported".equals(key))
                continue;

            if (value instanceof Boolean) {
                serializer.startTag(null, "setting");
                serializer.attribute(null, "key", key);
                serializer.attribute(null, "type", "boolean");
                serializer.attribute(null, "value", value.toString());
                serializer.endTag(null, "setting");

            } else if (value instanceof Integer) {
                serializer.startTag(null, "setting");
                serializer.attribute(null, "key", key);
                serializer.attribute(null, "type", "integer");
                serializer.attribute(null, "value", value.toString());
                serializer.endTag(null, "setting");

            } else if (value instanceof String) {
                serializer.startTag(null, "setting");
                serializer.attribute(null, "key", key);
                serializer.attribute(null, "type", "string");
                serializer.attribute(null, "value", value.toString());
                serializer.endTag(null, "setting");

            } else if (value instanceof Set) {
                Set<String> set = (Set<String>) value;
                serializer.startTag(null, "setting");
                serializer.attribute(null, "key", key);
                serializer.attribute(null, "type", "set");
                serializer.attribute(null, "value", TextUtils.join("\n", set));
                serializer.endTag(null, "setting");

            } else
                Log.e(TAG, "Unknown key=" + key);
        }
    }

    private void forwardExport(XmlSerializer serializer) throws IOException {
        try (Cursor cursor = DatabaseHelper.getInstance(this).getForwarding()) {
            int colProtocol = cursor.getColumnIndex("protocol");
            int colDPort = cursor.getColumnIndex("dport");
            int colRAddr = cursor.getColumnIndex("raddr");
            int colRPort = cursor.getColumnIndex("rport");
            int colRUid = cursor.getColumnIndex("ruid");
            while (cursor.moveToNext())
                for (String pkg : getPackages(cursor.getInt(colRUid))) {
                    serializer.startTag(null, "port");
                    serializer.attribute(null, "pkg", pkg);
                    serializer.attribute(null, "protocol", Integer.toString(cursor.getInt(colProtocol)));
                    serializer.attribute(null, "dport", Integer.toString(cursor.getInt(colDPort)));
                    serializer.attribute(null, "raddr", cursor.getString(colRAddr));
                    serializer.attribute(null, "rport", Integer.toString(cursor.getInt(colRPort)));
                    serializer.endTag(null, "port");
                }
        }
    }

    private String[] getPackages(int uid) {
        if (uid == 0)
            return new String[]{"root"};
        else if (uid == 1013)
            return new String[]{"mediaserver"};
        else if (uid == 9999)
            return new String[]{"nobody"};
        else {
            String pkgs[] = getPackageManager().getPackagesForUid(uid);
            if (pkgs == null)
                return new String[0];
            else
                return pkgs;
        }
    }

    private void xmlImport(InputStream in) throws IOException, SAXException, ParserConfigurationException {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.unregisterOnSharedPreferenceChangeListener(this);
        prefs.edit().putBoolean("enabled", false).apply();
        ServiceSinkhole.stop("import", this, false);

        XMLReader reader = SAXParserFactory.newInstance().newSAXParser().getXMLReader();
        XmlImportHandler handler = new XmlImportHandler(this);
        reader.setContentHandler(handler);
        reader.parse(new InputSource(in));

        xmlImport(handler.application, prefs);
        xmlImport(handler.wifi, getSharedPreferences("wifi", Context.MODE_PRIVATE));
        xmlImport(handler.mobile, getSharedPreferences("other", Context.MODE_PRIVATE));
        xmlImport(handler.screen_wifi, getSharedPreferences("screen_wifi", Context.MODE_PRIVATE));
        xmlImport(handler.screen_other, getSharedPreferences("screen_other", Context.MODE_PRIVATE));
        xmlImport(handler.roaming, getSharedPreferences("roaming", Context.MODE_PRIVATE));
        xmlImport(handler.lockdown, getSharedPreferences("lockdown", Context.MODE_PRIVATE));
        xmlImport(handler.apply, getSharedPreferences("apply", Context.MODE_PRIVATE));
        xmlImport(handler.notify, getSharedPreferences("notify", Context.MODE_PRIVATE));
        xmlImport(handler.blocklist, getSharedPreferences(PREF_BLOCKLIST, Context.MODE_PRIVATE));

        // Reload blocklist
        TrackerBlocklist.getInstance(this).loadSettings(this);
        InternetBlocklist.getInstance(this).loadSettings(this);

        // Upgrade imported settings
        ReceiverAutostart.upgrade(true, this);

        DatabaseHelper.clearCache();

        // Refresh UI
        prefs.edit().putBoolean("imported", true).apply();
        prefs.registerOnSharedPreferenceChangeListener(this);
    }

    private void xmlImport(Map<String, Object> settings, SharedPreferences prefs) {
        SharedPreferences.Editor editor = prefs.edit();

        // Clear existing setting
        for (String key : prefs.getAll().keySet())
            if (!"enabled".equals(key))
                editor.remove(key);

        // Apply new settings
        for (String key : settings.keySet()) {
            Object value = settings.get(key);
            if (value instanceof Boolean)
                editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer)
                editor.putInt(key, (Integer) value);
            else if (value instanceof String)
                editor.putString(key, (String) value);
            else if (value instanceof Set)
                editor.putStringSet(key, (Set<String>) value);
            else
                Log.e(TAG, "Unknown type=" + value.getClass());
        }

        editor.apply();
    }

    private class XmlImportHandler extends DefaultHandler {
        private Context context;
        public boolean enabled = false;
        public Map<String, Object> application = new HashMap<>();
        public Map<String, Object> wifi = new HashMap<>();
        public Map<String, Object> mobile = new HashMap<>();
        public Map<String, Object> screen_wifi = new HashMap<>();
        public Map<String, Object> screen_other = new HashMap<>();
        public Map<String, Object> roaming = new HashMap<>();
        public Map<String, Object> lockdown = new HashMap<>();
        public Map<String, Object> apply = new HashMap<>();
        public Map<String, Object> notify = new HashMap<>();
        public Map<String, Object> blocklist = new HashMap<>();
        private Map<String, Object> current = null;

        public XmlImportHandler(Context context) {
            this.context = context;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            if (qName.equals("netguard")
                    || qName.equals("trackercontrol"))
                ; // Ignore

            else if (qName.equals("application"))
                current = application;

            else if (qName.equals("wifi"))
                current = wifi;

            else if (qName.equals("mobile"))
                current = mobile;

            else if (qName.equals("screen_wifi"))
                current = screen_wifi;

            else if (qName.equals("screen_other"))
                current = screen_other;

            else if (qName.equals("roaming"))
                current = roaming;

            else if (qName.equals("lockdown"))
                current = lockdown;

            else if (qName.equals("apply"))
                current = apply;

            else if (qName.equals("notify"))
                current = notify;

            else if (qName.equals("forward")) {
                current = null;
                Log.i(TAG, "Clearing forwards");
                DatabaseHelper.getInstance(context).deleteForward();

            } else if (qName.equals("blocklist"))
                    current = blocklist;

            else if (qName.equals("setting")) {
                String key = attributes.getValue("key");
                String type = attributes.getValue("type");
                String value = attributes.getValue("value");

                if (current == null)
                    Log.e(TAG, "No current key=" + key);
                else {
                    if ("enabled".equals(key))
                        enabled = Boolean.parseBoolean(value);
                    else {
                        if (current == application) {
                            if ("hosts_last_import".equals(key) || "hosts_last_download".equals(key))
                                return;
                        }

                        if ("boolean".equals(type))
                            current.put(key, Boolean.parseBoolean(value));
                        else if ("integer".equals(type))
                            current.put(key, Integer.parseInt(value));
                        else if ("string".equals(type))
                            current.put(key, value);
                        else if ("set".equals(type)) {
                            Set<String> set = new HashSet<>();
                            if (!TextUtils.isEmpty(value))
                                for (String s : value.split("\n"))
                                    set.add(s);
                            current.put(key, set);
                        } else
                            Log.e(TAG, "Unknown type key=" + key);
                    }
                }

            } else if (qName.equals("port")) {
                String pkg = attributes.getValue("pkg");
                int protocol = Integer.parseInt(attributes.getValue("protocol"));
                int dport = Integer.parseInt(attributes.getValue("dport"));
                String raddr = attributes.getValue("raddr");
                int rport = Integer.parseInt(attributes.getValue("rport"));

                try {
                    int uid = getUid(pkg);
                    DatabaseHelper.getInstance(context).addForward(protocol, dport, raddr, rport, uid);
                } catch (PackageManager.NameNotFoundException ex) {
                    Log.w(TAG, "Package not found pkg=" + pkg);
                }

            } else
                Log.e(TAG, "Unknown element qname=" + qName);
        }

        private int getUid(String pkg) throws PackageManager.NameNotFoundException {
            if ("root".equals(pkg))
                return 0;
            else if ("android.media".equals(pkg))
                return 1013;
            else if ("android.multicast".equals(pkg))
                return 1020;
            else if ("android.gps".equals(pkg))
                return 1021;
            else if ("android.dns".equals(pkg))
                return 1051;
            else if ("nobody".equals(pkg))
                return 9999;
            else
                return getPackageManager().getApplicationInfo(pkg, 0).uid;
        }
    }
}
