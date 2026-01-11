/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * TrackerControl is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with TrackerControl. If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright © 2019–2020 Konrad Kollnig (University of Oxford)
 */

package net.kollnig.missioncontrol;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.Log;
import android.util.Pair;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.RecyclerView;

import net.kollnig.missioncontrol.data.InsightsData;

import java.io.File;
import java.io.FileOutputStream;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import eu.faircode.netguard.Util;

/**
 * Adapter that displays the insights hero card as a header in the main
 * RecyclerView.
 * Shows key stats (trackers blocked, companies detected) with share and "see
 * more" buttons.
 */
public class InsightsHeaderAdapter extends RecyclerView.Adapter<InsightsHeaderAdapter.ViewHolder> {

    private static final String TAG = "InsightsHeaderAdapter";
    private final Context context;
    private InsightsData data;
    private boolean visible = true;
    private final Executor executor = Executors.newSingleThreadExecutor();

    public InsightsHeaderAdapter(Context context) {
        this.context = context;
    }

    public void setData(InsightsData data) {
        this.data = data;
        notifyDataSetChanged();
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
        notifyDataSetChanged();
    }

    public int getItemCount() {
        return visible ? 1 : 0;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_insights_header, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.llContent.setVisibility(View.VISIBLE);
        holder.pbLoading.setVisibility(View.GONE);

        if (data == null) {
            // Show placeholder values until data loads
            holder.tvBlocked.setText("--");
            holder.tvCompanies.setText("--");
            return;
        }

        NumberFormat nf = NumberFormat.getNumberInstance(Locale.getDefault());
        holder.tvBlocked.setText(nf.format(data.getTotalTrackingAttempts()));
        holder.tvCompanies.setText(String.valueOf(data.getUniqueTrackerCompanies()));

        holder.btnShare.setOnClickListener(v -> shareInsights());
        holder.tvSeeMore.setOnClickListener(v -> {
            context.startActivity(new Intent(context, InsightsActivity.class));
        });
        holder.itemView.setOnClickListener(v -> {
            context.startActivity(new Intent(context, InsightsActivity.class));
        });
    }

    private void shareInsights() {
        if (data == null)
            return;

        executor.execute(() -> {
            try {
                File imageFile = generateShareImage();
                if (imageFile != null) {
                    android.os.Handler mainHandler = new android.os.Handler(context.getMainLooper());
                    mainHandler.post(() -> {
                        try {
                            android.net.Uri uri = FileProvider.getUriForFile(
                                    context,
                                    context.getPackageName() + ".provider",
                                    imageFile);

                            boolean isPlayStore = Util.isPlayStoreInstall();
                            int shareMsgRes = isPlayStore ? R.string.insights_share_message_playstore
                                    : R.string.insights_share_message;

                            String shareText = context.getString(
                                    shareMsgRes,
                                    isPlayStore ? data.getTotalTrackingAttempts() : data.getBlockedTrackingAttempts(),
                                    data.getUniqueTrackerCompanies());

                            Intent intent = new Intent(Intent.ACTION_SEND);
                            intent.setType("image/png");
                            intent.putExtra(Intent.EXTRA_STREAM, uri);
                            intent.putExtra(Intent.EXTRA_TEXT, shareText);
                            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                            context.startActivity(Intent.createChooser(intent,
                                    context.getString(R.string.insights_share))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to share", e);
                            Toast.makeText(context, R.string.export_failed, Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to generate share image", e);
            }
        });
    }

    private File generateShareImage() {
        try {
            // Inflate the share layout
            LayoutInflater inflater = LayoutInflater.from(context);
            View shareView = inflater.inflate(R.layout.layout_insights_share, null);

            // Find Views
            TextView tvTotalBlocked = shareView.findViewById(R.id.tvShareTotalBlocked);
            LinearLayout llBlockedStat = shareView.findViewById(R.id.llShareBlockedStat);
            TextView tvBlockedCount = shareView.findViewById(R.id.tvShareBlockedCount);
            TextView tvCompanies = shareView.findViewById(R.id.tvShareCompanies);
            LinearLayout llTopCompanies = shareView.findViewById(R.id.llShareTopCompanies);

            NumberFormat nf = NumberFormat.getNumberInstance(Locale.getDefault());

            // Hero stat: Total Hosts
            tvTotalBlocked.setText(nf.format(data.getTotalTrackingAttempts()));

            // Blocked stat: hide on Play Store
            if (Util.isPlayStoreInstall()) {
                llBlockedStat.setVisibility(View.GONE);
            } else {
                llBlockedStat.setVisibility(View.VISIBLE);
                tvBlockedCount.setText(nf.format(data.getBlockedTrackingAttempts()));
            }

            // Companies count
            tvCompanies.setText(String.valueOf(data.getUniqueTrackerCompanies()));

            // Top 3 Companies - use pervasiveTrackers for correct app counts
            List<Pair<String, Integer>> top3 = data.getPervasiveTrackers().subList(0,
                    Math.min(data.getPervasiveTrackers().size(), 3));
            float density = context.getResources().getDisplayMetrics().density;

            for (Pair<String, Integer> company : top3) {
                LinearLayout row = new LinearLayout(context);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                rowParams.topMargin = (int) (4 * density);
                row.setLayoutParams(rowParams);
                row.setOrientation(LinearLayout.HORIZONTAL);

                TextView nameView = new TextView(context);
                nameView.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                nameView.setText(company.first);
                nameView.setTextColor(Color.WHITE);
                nameView.setTextSize(12f);

                TextView countView = new TextView(context);
                countView.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
                countView.setText(context.getString(R.string.insights_in_apps, company.second));
                countView.setTextColor(Color.WHITE);
                countView.setTextSize(12f);
                countView.setTypeface(null, Typeface.BOLD);

                row.addView(nameView);
                row.addView(countView);
                llTopCompanies.addView(row);
            }

            // Measure and layout the view
            int widthPx = (int) TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 400f,
                    context.getResources().getDisplayMetrics());
            int widthSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY);
            int heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);

            shareView.measure(widthSpec, heightSpec);
            shareView.layout(0, 0, shareView.getMeasuredWidth(), shareView.getMeasuredHeight());

            // Create bitmap and draw
            Bitmap bitmap = Bitmap.createBitmap(
                    shareView.getMeasuredWidth(),
                    shareView.getMeasuredHeight(),
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            shareView.draw(canvas);

            // Save to cache directory
            File shareDir = new File(context.getCacheDir(), "share");
            if (!shareDir.exists()) {
                shareDir.mkdirs();
            }

            File imageFile = new File(shareDir, "trackercontrol_insights.png");
            try (FileOutputStream out = new FileOutputStream(imageFile)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            }

            bitmap.recycle();
            return imageFile;
        } catch (Exception e) {
            Log.e(TAG, "Failed to generate share image", e);
            return null;
        }
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ProgressBar pbLoading;
        LinearLayout llContent;
        TextView tvBlocked;
        TextView tvCompanies;
        ImageButton btnShare;
        TextView tvSeeMore;

        ViewHolder(View itemView) {
            super(itemView);
            pbLoading = itemView.findViewById(R.id.pbLoading);
            llContent = itemView.findViewById(R.id.llContent);
            tvBlocked = itemView.findViewById(R.id.tvHeroBlocked);
            tvCompanies = itemView.findViewById(R.id.tvHeroCompanies);
            btnShare = itemView.findViewById(R.id.btnShare);
            tvSeeMore = itemView.findViewById(R.id.tvSeeMore);
        }
    }
}
