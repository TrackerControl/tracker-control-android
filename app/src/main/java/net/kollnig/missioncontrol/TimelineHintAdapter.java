package net.kollnig.missioncontrol;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

public class TimelineHintAdapter extends RecyclerView.Adapter<TimelineHintAdapter.ViewHolder> {

    private final Runnable onDismiss;
    private boolean visible = false;

    public TimelineHintAdapter(Runnable onDismiss) {
        this.onDismiss = onDismiss;
    }

    public void setVisible(boolean visible) {
        if (this.visible == visible)
            return;
        this.visible = visible;
        if (visible)
            notifyItemInserted(0);
        else
            notifyItemRemoved(0);
    }

    @Override
    public int getItemCount() {
        return visible ? 1 : 0;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_timeline_hint, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.tvHintText.setText(R.string.timeline_hint_tap_entry);
        holder.btnHintDismiss.setText(R.string.timeline_hint_dismiss);
        holder.btnHintDismiss.setOnClickListener(v -> onDismiss.run());
    }

    static boolean shouldShow(boolean hasEntries, boolean hintEnabled) {
        return hasEntries && hintEnabled;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvHintText;
        MaterialButton btnHintDismiss;

        ViewHolder(View itemView) {
            super(itemView);
            tvHintText = itemView.findViewById(R.id.tvHintText);
            btnHintDismiss = itemView.findViewById(R.id.btnHintDismiss);
        }
    }
}
