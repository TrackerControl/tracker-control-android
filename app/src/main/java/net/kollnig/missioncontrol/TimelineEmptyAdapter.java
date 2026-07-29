package net.kollnig.missioncontrol;

import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import eu.faircode.netguard.ActivitySettings;

public class TimelineEmptyAdapter extends RecyclerView.Adapter<TimelineEmptyAdapter.ViewHolder> {

    private boolean visible = false;
    private boolean trackerControlEnabled = false;
    private boolean trackerRecordingEnabled = true;

    public void setVisible(boolean visible) {
        if (this.visible == visible)
            return;
        this.visible = visible;
        if (visible)
            notifyItemInserted(0);
        else
            notifyItemRemoved(0);
    }

    public void setTrackerControlEnabled(boolean enabled) {
        if (this.trackerControlEnabled == enabled)
            return;
        this.trackerControlEnabled = enabled;
        if (visible)
            notifyItemChanged(0);
    }

    /**
     * Whether tracker access is being recorded ("Search new trackers", the
     * log_app setting). With it off, trackers are still blocked but never
     * written to the access table, so the Timeline stays empty forever —
     * saying "Watching for trackers…" would be untrue.
     */
    public void setTrackerRecordingEnabled(boolean enabled) {
        if (this.trackerRecordingEnabled == enabled)
            return;
        this.trackerRecordingEnabled = enabled;
        if (visible)
            notifyItemChanged(0);
    }

    @Override
    public int getItemCount() {
        return visible ? 1 : 0;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_timeline_empty, parent, false);
        return new ViewHolder(view);
    }

    /**
     * Which explanation the empty Timeline should show. Recording off is a
     * distinct state from "nothing seen yet": it never resolves on its own.
     */
    enum EmptyState {
        TRACKER_CONTROL_OFF,
        RECORDING_OFF,
        WATCHING
    }

    static EmptyState stateFor(boolean trackerControlEnabled, boolean trackerRecordingEnabled) {
        if (!trackerControlEnabled)
            return EmptyState.TRACKER_CONTROL_OFF;
        if (!trackerRecordingEnabled)
            return EmptyState.RECORDING_OFF;
        return EmptyState.WATCHING;
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        switch (stateFor(trackerControlEnabled, trackerRecordingEnabled)) {
            case TRACKER_CONTROL_OFF:
                holder.tvTitle.setText(R.string.timeline_empty_disabled_title);
                holder.tvSubtitle.setText(R.string.timeline_empty_disabled_subtitle);
                holder.btnOpenApp.setVisibility(View.GONE);
                holder.btnOpenApp.setOnClickListener(null);
                break;

            case RECORDING_OFF:
                holder.tvTitle.setText(R.string.timeline_empty_recording_off_title);
                holder.tvSubtitle.setText(R.string.timeline_empty_recording_off_subtitle);
                holder.btnOpenApp.setVisibility(View.VISIBLE);
                holder.btnOpenApp.setText(R.string.timeline_open_settings);
                holder.btnOpenApp.setOnClickListener(v -> v.getContext().startActivity(
                        new Intent(v.getContext(), ActivitySettings.class)));
                break;

            default:
                holder.tvTitle.setText(R.string.timeline_empty_enabled_title);
                holder.tvSubtitle.setText(R.string.timeline_empty_enabled_subtitle);
                holder.btnOpenApp.setVisibility(View.VISIBLE);
                holder.btnOpenApp.setText(R.string.timeline_open_app);
                holder.btnOpenApp.setOnClickListener(v -> {
                    Intent home = new Intent(Intent.ACTION_MAIN);
                    home.addCategory(Intent.CATEGORY_HOME);
                    home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    v.getContext().startActivity(home);
                });
                break;
        }
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvTitle;
        TextView tvSubtitle;
        MaterialButton btnOpenApp;

        ViewHolder(View itemView) {
            super(itemView);
            tvTitle = itemView.findViewById(R.id.tvEmptyTitle);
            tvSubtitle = itemView.findViewById(R.id.tvEmptySubtitle);
            btnOpenApp = itemView.findViewById(R.id.btnOpenApp);
        }
    }
}
