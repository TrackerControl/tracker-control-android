package net.kollnig.missioncontrol;

import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

public class TimelineEmptyAdapter extends RecyclerView.Adapter<TimelineEmptyAdapter.ViewHolder> {

    private boolean visible = false;
    private boolean trackerControlEnabled = false;

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

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        if (trackerControlEnabled) {
            holder.tvTitle.setText(R.string.timeline_empty_enabled_title);
            holder.tvSubtitle.setText(R.string.timeline_empty_enabled_subtitle);
            holder.btnOpenApp.setVisibility(View.VISIBLE);
            holder.btnOpenApp.setOnClickListener(v -> {
                Intent home = new Intent(Intent.ACTION_MAIN);
                home.addCategory(Intent.CATEGORY_HOME);
                home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                v.getContext().startActivity(home);
            });
        } else {
            holder.tvTitle.setText(R.string.timeline_empty_disabled_title);
            holder.tvSubtitle.setText(R.string.timeline_empty_disabled_subtitle);
            holder.btnOpenApp.setVisibility(View.GONE);
            holder.btnOpenApp.setOnClickListener(null);
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
