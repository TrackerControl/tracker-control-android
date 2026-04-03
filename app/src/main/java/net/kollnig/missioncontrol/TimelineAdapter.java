package net.kollnig.missioncontrol;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import net.kollnig.missioncontrol.data.TimelineEntry;
import net.kollnig.missioncontrol.data.TrackerContact;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class TimelineAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_SECTION = 0;
    private static final int TYPE_ENTRY = 1;
    private static final int MAX_TRACKERS_SHOWN = 3;

    private final Context context;
    private final PackageManager pm;
    private final OnEntryClickListener listener;
    private final List<Object> items = new ArrayList<>();

    public interface OnEntryClickListener {
        void onEntryClick(TimelineEntry entry);
    }

    public TimelineAdapter(Context context, OnEntryClickListener listener) {
        this.context = context;
        this.pm = context.getPackageManager();
        this.listener = listener;
    }

    public void setEntries(List<TimelineEntry> entries) {
        items.clear();

        if (entries.isEmpty()) {
            notifyDataSetChanged();
            return;
        }

        long now = System.currentTimeMillis();
        long oneHourAgo = now - 60 * 60 * 1000L;
        long startOfToday = getStartOfDay(0);
        long startOfYesterday = getStartOfDay(1);
        long startOfWeek = now - 7L * 24 * 60 * 60 * 1000;

        String currentSection = null;
        for (TimelineEntry entry : entries) {
            String section;
            if (entry.mostRecentTime >= oneHourAgo) {
                section = context.getString(R.string.timeline_section_last_hour);
            } else if (entry.mostRecentTime >= startOfToday) {
                section = context.getString(R.string.timeline_section_today);
            } else if (entry.mostRecentTime >= startOfYesterday) {
                section = context.getString(R.string.timeline_section_yesterday);
            } else {
                section = context.getString(R.string.timeline_section_this_week);
            }

            if (!section.equals(currentSection)) {
                items.add(section);
                currentSection = section;
            }
            items.add(entry);
        }
        notifyDataSetChanged();
    }

    private long getStartOfDay(int daysAgo) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, -daysAgo);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position) instanceof String ? TYPE_SECTION : TYPE_ENTRY;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_SECTION) {
            View view = inflater.inflate(R.layout.item_timeline_section, parent, false);
            return new SectionHolder(view);
        } else {
            View view = inflater.inflate(R.layout.item_timeline_entry, parent, false);
            return new EntryHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof SectionHolder) {
            ((SectionHolder) holder).tvSection.setText((String) items.get(position));
        } else {
            bindEntry((EntryHolder) holder, (TimelineEntry) items.get(position));
        }
    }

    private void bindEntry(EntryHolder holder, TimelineEntry entry) {
        // App icon
        try {
            ApplicationInfo ai = pm.getApplicationInfo(entry.packageName, 0);
            Drawable icon = pm.getApplicationIcon(ai);
            holder.ivAppIcon.setImageDrawable(icon);
        } catch (PackageManager.NameNotFoundException e) {
            holder.ivAppIcon.setImageResource(android.R.drawable.sym_def_app_icon);
        }

        // App name
        holder.tvAppName.setText(entry.appName);

        // Relative time
        CharSequence relativeTime = DateUtils.getRelativeTimeSpanString(
                entry.mostRecentTime, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE);
        holder.tvTime.setText(relativeTime);

        // Tracker list
        holder.llTrackers.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(context);

        int shown = 0;
        for (TrackerContact tc : entry.trackers) {
            if (shown >= MAX_TRACKERS_SHOWN) {
                int remaining = entry.trackers.size() - MAX_TRACKERS_SHOWN;
                TextView overflow = new TextView(context);
                overflow.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
                overflow.setTextColor(context.getColor(android.R.color.darker_gray));
                overflow.setText(context.getString(R.string.timeline_more_trackers, remaining));
                holder.llTrackers.addView(overflow);
                break;
            }

            TextView tv = new TextView(context);
            tv.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);

            String prefix = tc.blocked ? "\u26D4 " : "\u2705 ";
            String label = tc.companyName;
            if (tc.category != null)
                label += " \u00B7 " + tc.category;
            tv.setText(prefix + label);
            holder.llTrackers.addView(tv);
            shown++;
        }

        // Click listener
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onEntryClick(entry);
        });
    }

    static class SectionHolder extends RecyclerView.ViewHolder {
        final TextView tvSection;

        SectionHolder(View itemView) {
            super(itemView);
            tvSection = itemView.findViewById(R.id.tvSection);
        }
    }

    static class EntryHolder extends RecyclerView.ViewHolder {
        final ImageView ivAppIcon;
        final TextView tvAppName;
        final TextView tvTime;
        final LinearLayout llTrackers;

        EntryHolder(View itemView) {
            super(itemView);
            ivAppIcon = itemView.findViewById(R.id.ivAppIcon);
            tvAppName = itemView.findViewById(R.id.tvAppName);
            tvTime = itemView.findViewById(R.id.tvTime);
            llTrackers = itemView.findViewById(R.id.llTrackers);
        }
    }
}
