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

import static net.kollnig.missioncontrol.DetailsActivity.INTENT_EXTRA_APP_NAME;
import static net.kollnig.missioncontrol.DetailsActivity.INTENT_EXTRA_APP_PACKAGENAME;
import static net.kollnig.missioncontrol.DetailsActivity.INTENT_EXTRA_APP_UID;
import static eu.faircode.netguard.ActivityMain.REQUEST_DETAILS_UPDATED;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Rect;
import android.net.Uri;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.TouchDelegate;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.request.RequestOptions;
import com.google.android.material.snackbar.Snackbar;

import net.kollnig.missioncontrol.Common;
import net.kollnig.missioncontrol.DetailsActivity;
import net.kollnig.missioncontrol.R;
import net.kollnig.missioncontrol.data.BlockingMode;
import net.kollnig.missioncontrol.data.InternetBlocklist;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class AdapterRule extends RecyclerView.Adapter<AdapterRule.ViewHolder> implements Filterable {
    private static final String TAG = "TrackerControl.Adapter";

    private View anchor;
    private LayoutInflater inflater;
    private RecyclerView rv;
    private int colorText;
    private int colorChanged;
    private int colorOn;
    private int colorOff;
    private int colorGrayed;
    private int iconSize;
    private boolean wifiActive = true;
    private boolean otherActive = true;
    private boolean live = true;
    private List<Rule> listAll = new ArrayList<>();
    private List<Rule> listFiltered = new ArrayList<>();
    private final RequestOptions glideOptions;

    private List<String> messaging = Arrays.asList(
            "com.discord",
            "com.facebook.mlite",
            "com.facebook.orca",
            "com.instagram.android",
            "com.Slack",
            "com.skype.raider",
            "com.snapchat.android",
            "com.whatsapp",
            "com.whatsapp.w4b");

    private List<String> download = Arrays.asList(
            "com.google.android.youtube");

    public static class ViewHolder extends RecyclerView.ViewHolder {
        public View view;

        public LinearLayout llApplication;
        public ImageView ivIcon;
        public ImageView ivExpander;
        public TextView tvName;

        public TextView tvHosts;

        public RelativeLayout rlLockdown;
        public ImageView ivLockdown;

        public CheckBox cbWifi;
        public ImageView ivScreenWifi;

        public CheckBox cbOther;
        public ImageView ivScreenOther;
        public TextView tvRoaming;

        public TextView tvRemarkMessaging;
        public TextView tvRemarkDownload;

        public LinearLayout llConfiguration;
        public TextView tvUid;
        public TextView tvPackage;
        public TextView tvVersion;
        public TextView tvInternet;
        public TextView tvDisabled;

        public Button btnRelated;
        public ImageButton ibSettings;
        public ImageButton ibLaunch;

        public Switch cbApply;

        public LinearLayout llScreenWifi;
        public ImageView ivWifiLegend;
        public CheckBox cbScreenWifi;

        public LinearLayout llScreenOther;
        public ImageView ivOtherLegend;
        public CheckBox cbScreenOther;

        public CheckBox cbRoaming;

        public CheckBox cbLockdown;
        public ImageView ivLockdownLegend;

        public ImageButton btnClear;

        public LinearLayout llFilter;
        public ImageView ivLive;
        public TextView tvLogging;
        public Button btnLogging;
        public ListView lvAccess;
        public ImageButton btnClearAccess;
        public CheckBox cbNotify;

        // Custom code
        private final TextView tvDetails;

        public ViewHolder(View itemView) {
            super(itemView);
            view = itemView;

            llApplication = itemView.findViewById(R.id.llApplication);
            ivIcon = itemView.findViewById(R.id.ivIcon);

            ivExpander = itemView.findViewById(R.id.ivExpander);
            tvName = itemView.findViewById(R.id.tvName);

            tvHosts = itemView.findViewById(R.id.tvHosts);

            rlLockdown = itemView.findViewById(R.id.rlLockdown);
            ivLockdown = itemView.findViewById(R.id.ivLockdown);

            cbWifi = itemView.findViewById(R.id.cbWifi);
            ivScreenWifi = itemView.findViewById(R.id.ivScreenWifi);

            cbOther = itemView.findViewById(R.id.cbOther);
            ivScreenOther = itemView.findViewById(R.id.ivScreenOther);
            tvRoaming = itemView.findViewById(R.id.tvRoaming);

            tvRemarkMessaging = itemView.findViewById(R.id.tvRemarkMessaging);
            tvRemarkDownload = itemView.findViewById(R.id.tvRemarkDownload);

            llConfiguration = itemView.findViewById(R.id.llConfiguration);
            tvUid = itemView.findViewById(R.id.tvUid);
            tvPackage = itemView.findViewById(R.id.tvPackage);
            tvVersion = itemView.findViewById(R.id.tvVersion);
            tvInternet = itemView.findViewById(R.id.tvInternet);
            tvDisabled = itemView.findViewById(R.id.tvDisabled);

            btnRelated = itemView.findViewById(R.id.btnRelated);
            ibSettings = itemView.findViewById(R.id.ibSettings);
            ibLaunch = itemView.findViewById(R.id.ibLaunch);

            cbApply = itemView.findViewById(R.id.cbApply);
            tvDetails = itemView.findViewById(R.id.app_details);

            llScreenWifi = itemView.findViewById(R.id.llScreenWifi);
            ivWifiLegend = itemView.findViewById(R.id.ivWifiLegend);
            cbScreenWifi = itemView.findViewById(R.id.cbScreenWifi);

            llScreenOther = itemView.findViewById(R.id.llScreenOther);
            ivOtherLegend = itemView.findViewById(R.id.ivOtherLegend);
            cbScreenOther = itemView.findViewById(R.id.cbScreenOther);

            cbRoaming = itemView.findViewById(R.id.cbRoaming);

            cbLockdown = itemView.findViewById(R.id.cbLockdown);
            ivLockdownLegend = itemView.findViewById(R.id.ivLockdownLegend);

            btnClear = itemView.findViewById(R.id.btnClear);

            llFilter = itemView.findViewById(R.id.llFilter);
            ivLive = itemView.findViewById(R.id.ivLive);
            tvLogging = itemView.findViewById(R.id.tvLogging);
            btnLogging = itemView.findViewById(R.id.btnLogging);
            lvAccess = itemView.findViewById(R.id.lvAccess);
            btnClearAccess = itemView.findViewById(R.id.btnClearAccess);
            cbNotify = itemView.findViewById(R.id.cbNotify);

            final View wifiParent = (View) cbWifi.getParent();
            wifiParent.post(new Runnable() {
                public void run() {
                    Rect rect = new Rect();
                    cbWifi.getHitRect(rect);
                    rect.bottom += rect.top;
                    rect.right += rect.left;
                    rect.top = 0;
                    rect.left = 0;
                    wifiParent.setTouchDelegate(new TouchDelegate(rect, cbWifi));
                }
            });

            final View otherParent = (View) cbOther.getParent();
            otherParent.post(new Runnable() {
                public void run() {
                    Rect rect = new Rect();
                    cbOther.getHitRect(rect);
                    rect.bottom += rect.top;
                    rect.right += rect.left;
                    rect.top = 0;
                    rect.left = 0;
                    otherParent.setTouchDelegate(new TouchDelegate(rect, cbOther));
                }
            });
        }
    }

    public AdapterRule(Context context, View anchor) {
        this.anchor = anchor;
        this.inflater = LayoutInflater.from(context);
        this.glideOptions = new RequestOptions().format(DecodeFormat.PREFER_RGB_565);

        if (Common.isNight(context))
            colorChanged = Color.argb(128, Color.red(Color.DKGRAY), Color.green(Color.DKGRAY),
                    Color.blue(Color.DKGRAY));
        else
            colorChanged = Color.argb(128, 230, 230, 230);

        TypedArray ta = context.getTheme().obtainStyledAttributes(new int[] { android.R.attr.textColorPrimary });
        try {
            colorText = ta.getColor(0, 0);
        } finally {
            ta.recycle();
        }

        TypedValue tv = new TypedValue();
        context.getTheme().resolveAttribute(R.attr.colorOn, tv, true);
        colorOn = tv.data;
        context.getTheme().resolveAttribute(R.attr.colorOff, tv, true);
        colorOff = tv.data;

        colorGrayed = ContextCompat.getColor(context, R.color.colorGrayed);

        TypedValue typedValue = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.listPreferredItemHeight, typedValue, true);
        int height = TypedValue.complexToDimensionPixelSize(typedValue.data,
                context.getResources().getDisplayMetrics());
        this.iconSize = Math.round(height * context.getResources().getDisplayMetrics().density + 0.5f);

        setHasStableIds(true);
    }

    public void set(List<Rule> listRule) {
        listAll = listRule;
        List<Rule> oldList = listFiltered;
        listFiltered = new ArrayList<>(listRule);
        DiffUtil.DiffResult result = DiffUtil.calculateDiff(new RuleDiffCallback(oldList, listFiltered));
        result.dispatchUpdatesTo(this);
    }

    private static class RuleDiffCallback extends DiffUtil.Callback {
        private final List<Rule> oldList;
        private final List<Rule> newList;

        RuleDiffCallback(List<Rule> oldList, List<Rule> newList) {
            this.oldList = oldList;
            this.newList = newList;
        }

        @Override
        public int getOldListSize() {
            return oldList.size();
        }

        @Override
        public int getNewListSize() {
            return newList.size();
        }

        @Override
        public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
            Rule oldRule = oldList.get(oldItemPosition);
            Rule newRule = newList.get(newItemPosition);
            return oldRule.uid == newRule.uid && oldRule.packageName.equals(newRule.packageName);
        }

        @Override
        public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
            Rule oldRule = oldList.get(oldItemPosition);
            Rule newRule = newList.get(newItemPosition);
            return oldRule.uid == newRule.uid
                    && oldRule.packageName.equals(newRule.packageName)
                    && oldRule.expanded == newRule.expanded
                    && oldRule.changed == newRule.changed
                    && oldRule.apply == newRule.apply
                    && oldRule.hosts == newRule.hosts;
        }
    }

    public void setWifiActive() {
        wifiActive = true;
        otherActive = false;
        notifyDataSetChanged();
    }

    public void setMobileActive() {
        wifiActive = false;
        otherActive = true;
        notifyDataSetChanged();
    }

    public void setDisconnected() {
        wifiActive = false;
        otherActive = false;
        notifyDataSetChanged();
    }

    public boolean isLive() {
        return this.live;
    }

    @Override
    public void onAttachedToRecyclerView(RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        rv = recyclerView;
    }

    @Override
    public void onDetachedFromRecyclerView(RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        rv = null;
    }

    @Override
    public void onBindViewHolder(final ViewHolder holder, int position) {
        final Context context = holder.itemView.getContext();

        // Get rule
        final Rule rule = listFiltered.get(position);
        // In minimal mode, tracker_protect is not user-controllable
        final boolean active = BlockingMode.isMinimalMode(context)
                ? rule.apply
                : rule.apply && rule.tracker_protect;

        // Show if non default rules
        holder.itemView.setBackgroundColor(rule.changed ? colorChanged : Color.TRANSPARENT);

        // Show application icon
        if (rule.icon <= 0)
            holder.ivIcon.setImageResource(android.R.drawable.sym_def_app_icon);
        else {
            Uri uri = Uri.parse("android.resource://" + rule.packageName + "/" + rule.icon);
            GlideApp.with(holder.itemView.getContext())
                    .applyDefaultRequestOptions(glideOptions)
                    .load(uri)
                    .override(iconSize, iconSize)
                    .into(holder.ivIcon);
        }

        // Show application label
        holder.tvName.setText(rule.name);

        // Show application state
        holder.tvName.setTextColor(rule.system ? colorOff : colorText);

        // Show if Internet access blocked
        final ImageView iv = holder.ivIcon;
        InternetBlocklist internetBlocklist = InternetBlocklist.getInstance(context);
        setGreyscale(iv, rule.internet && active && internetBlocklist.blockedInternet(rule.uid));
        holder.ivIcon.setOnClickListener(view -> {
            if (!rule.internet)
                return;

            if (!active) {
                Snackbar s = Common.getSnackbar((Activity) context, R.string.bypass_vpn_error);
                if (s != null)
                    s.show();
                return;
            }

            boolean wasBlocked = internetBlocklist.blockedInternet(rule.uid);
            if (wasBlocked) {
                internetBlocklist.unblock(rule.uid);
                Toast.makeText(context, R.string.internet_unblocked, Toast.LENGTH_SHORT).show();
            } else {
                internetBlocklist.block(rule.uid);
                Toast.makeText(context, R.string.internet_blocked, Toast.LENGTH_SHORT).show();
            }
            setGreyscale(iv, !wasBlocked);
        });

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String sort = prefs.getString("sort", "trackers_week");

        boolean pastWeekOnly = !("trackers_all".equals(sort));
        final int trackerCount = rule.getTrackerCount(pastWeekOnly);
        if (trackerCount > 0) {
            holder.tvDetails.setVisibility(View.VISIBLE);
            if (pastWeekOnly)
                holder.tvDetails.setText(context.getResources().getQuantityString(
                        R.plurals.n_companies_found_week, trackerCount, trackerCount));
            else
                holder.tvDetails.setText(context.getResources().getQuantityString(
                        R.plurals.n_companies_found, trackerCount, trackerCount));
        } else if (!rule.internet) {
            holder.tvDetails.setVisibility(View.VISIBLE);
            holder.tvDetails.setText(R.string.no_internet);
        } else
            holder.tvDetails.setVisibility(View.GONE);

        holder.itemView.setOnClickListener(view -> {
            if (!rule.internet) {
                Snackbar s = Common.getSnackbar((Activity) context, R.string.no_internet_message);
                if (s != null)
                    s.show();
            } else {
                final Intent settings = new Intent(context, DetailsActivity.class);
                settings.putExtra(INTENT_EXTRA_APP_NAME, rule.name);
                settings.putExtra(INTENT_EXTRA_APP_PACKAGENAME, rule.packageName);
                settings.putExtra(INTENT_EXTRA_APP_UID, rule.uid);
                ((Activity) context).startActivityForResult(settings, REQUEST_DETAILS_UPDATED);
            }
        });

        // NOTE: Views inside the legacy GONE LinearLayout in rule.xml (llApplication,
        // ivExpander, tvHosts, rlLockdown, llConfiguration, lvAccess, ivLive,
        // btnLogging, cbNotify, btnClearAccess, etc.) are never visible.
        // All binding work for those views has been removed to improve scroll performance.
        // The legacy expanded-state code (database queries, AdapterAccess creation,
        // click listeners on hidden views) was the primary cause of scroll jank.
    }

    private void setGreyscale(ImageView iv, boolean on) {
        if (on) {
            ColorMatrix matrix = new ColorMatrix();
            matrix.setSaturation(0); // 0 means grayscale
            ColorMatrixColorFilter cf = new ColorMatrixColorFilter(matrix);
            iv.setColorFilter(cf);
            iv.setImageAlpha(128); // 128 = 0.5
        } else {
            iv.setColorFilter(null);
            iv.setImageAlpha(255);
        }
    }

    @Override
    public void onViewRecycled(ViewHolder holder) {
        super.onViewRecycled(holder);
    }

    private void updateRule(Context context, Rule rule, boolean root, List<Rule> listAll) {
        SharedPreferences wifi = context.getSharedPreferences("wifi", Context.MODE_PRIVATE);
        SharedPreferences other = context.getSharedPreferences("other", Context.MODE_PRIVATE);
        SharedPreferences apply = context.getSharedPreferences("apply", Context.MODE_PRIVATE);
        SharedPreferences tracker_protect = context.getSharedPreferences("tracker_protect", Context.MODE_PRIVATE);
        SharedPreferences screen_wifi = context.getSharedPreferences("screen_wifi", Context.MODE_PRIVATE);
        SharedPreferences screen_other = context.getSharedPreferences("screen_other", Context.MODE_PRIVATE);
        SharedPreferences roaming = context.getSharedPreferences("roaming", Context.MODE_PRIVATE);
        SharedPreferences lockdown = context.getSharedPreferences("lockdown", Context.MODE_PRIVATE);
        SharedPreferences notify = context.getSharedPreferences("notify", Context.MODE_PRIVATE);

        if (rule.wifi_blocked == rule.wifi_default)
            wifi.edit().remove(rule.packageName).apply();
        else
            wifi.edit().putBoolean(rule.packageName, rule.wifi_blocked).apply();

        if (rule.other_blocked == rule.other_default)
            other.edit().remove(rule.packageName).apply();
        else
            other.edit().putBoolean(rule.packageName, rule.other_blocked).apply();

        apply.edit().putBoolean(rule.packageName, rule.apply).apply();
        tracker_protect.edit().putBoolean(rule.packageName, rule.tracker_protect).apply();

        if (rule.screen_wifi == rule.screen_wifi_default)
            screen_wifi.edit().remove(rule.packageName).apply();
        else
            screen_wifi.edit().putBoolean(rule.packageName, rule.screen_wifi).apply();

        if (rule.screen_other == rule.screen_other_default)
            screen_other.edit().remove(rule.packageName).apply();
        else
            screen_other.edit().putBoolean(rule.packageName, rule.screen_other).apply();

        if (rule.roaming == rule.roaming_default)
            roaming.edit().remove(rule.packageName).apply();
        else
            roaming.edit().putBoolean(rule.packageName, rule.roaming).apply();

        if (rule.lockdown)
            lockdown.edit().putBoolean(rule.packageName, rule.lockdown).apply();
        else
            lockdown.edit().remove(rule.packageName).apply();

        if (rule.notify)
            notify.edit().remove(rule.packageName).apply();
        else
            notify.edit().putBoolean(rule.packageName, rule.notify).apply();

        rule.updateChanged(context);
        Log.i(TAG, "Updated " + rule);

        List<Rule> listModified = new ArrayList<>();
        for (String pkg : rule.related) {
            for (Rule related : listAll)
                if (related.packageName.equals(pkg)) {
                    related.wifi_blocked = rule.wifi_blocked;
                    related.other_blocked = rule.other_blocked;
                    related.apply = rule.apply;
                    related.tracker_protect = rule.tracker_protect;
                    related.screen_wifi = rule.screen_wifi;
                    related.screen_other = rule.screen_other;
                    related.roaming = rule.roaming;
                    related.lockdown = rule.lockdown;
                    related.notify = rule.notify;
                    listModified.add(related);
                }
        }

        List<Rule> listSearch = (root ? new ArrayList<>(listAll) : listAll);
        listSearch.remove(rule);
        for (Rule modified : listModified)
            listSearch.remove(modified);
        for (Rule modified : listModified)
            updateRule(context, modified, false, listSearch);

        if (root) {
            notifyDataSetChanged();
            NotificationManagerCompat.from(context).cancel(rule.uid);
            ServiceSinkhole.reload("rule changed", context, false);
        }
    }

    @Override
    public Filter getFilter() {
        return new Filter() {
            @Override
            protected FilterResults performFiltering(CharSequence query) {
                List<Rule> listResult = new ArrayList<>();
                if (query == null)
                    listResult.addAll(listAll);
                else {
                    String queryStr = query.toString().toLowerCase().trim();
                    int uid;
                    try {
                        uid = Integer.parseInt(queryStr);
                    } catch (NumberFormatException ignore) {
                        uid = -1;
                    }
                    for (Rule rule : listAll)
                        if (rule.uid == uid ||
                                rule.packageName.toLowerCase().contains(queryStr) ||
                                (rule.name != null && rule.name.toLowerCase().contains(queryStr)))
                            listResult.add(rule);
                }

                FilterResults result = new FilterResults();
                result.values = listResult;
                result.count = listResult.size();
                return result;
            }

            @Override
            protected void publishResults(CharSequence query, FilterResults result) {
                List<Rule> oldList = listFiltered;
                List<Rule> newFiltered = new ArrayList<>();
                if (result == null)
                    newFiltered.addAll(listAll);
                else {
                    newFiltered.addAll((List<Rule>) result.values);
                    if (newFiltered.size() == 1)
                        newFiltered.get(0).expanded = true;
                }
                listFiltered = newFiltered;
                DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(new RuleDiffCallback(oldList, listFiltered));
                diffResult.dispatchUpdatesTo(AdapterRule.this);
            }
        };
    }

    @Override
    public AdapterRule.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        return new ViewHolder(inflater.inflate(R.layout.rule, parent, false));
    }

    @Override
    public long getItemId(int position) {
        Rule rule = listFiltered.get(position);
        return rule.packageName.hashCode() * 100000L + rule.uid;
    }

    @Override
    public int getItemCount() {
        return listFiltered.size();
    }
}
