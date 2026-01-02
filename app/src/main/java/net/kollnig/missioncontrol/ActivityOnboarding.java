package net.kollnig.missioncontrol;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import java.util.ArrayList;
import java.util.List;

import eu.faircode.netguard.ActivityMain;
import eu.faircode.netguard.ServiceSinkhole;
import eu.faircode.netguard.Util;

public class ActivityOnboarding extends AppCompatActivity {

    private ViewPager2 viewPager;
    private Button btnNext;
    private Button btnPrevious;
    private List<Slide> slides = new ArrayList<>();
    private OnboardingAdapter adapter;
    private boolean slidesInitialized = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_onboarding);

        viewPager = findViewById(R.id.viewPager);
        btnNext = findViewById(R.id.btnNext);
        btnPrevious = findViewById(R.id.btnPrevious);

        setupSlides();

        btnNext.setOnClickListener(v -> {
            if (viewPager.getCurrentItem() < adapter.getItemCount() - 1) {
                int position = viewPager.getCurrentItem();
                Slide currentSlide = slides.get(position);

                Runnable next = () -> viewPager.setCurrentItem(position + 1);

                if (currentSlide.warningResId != 0) {
                    Util.areYouSure(this, currentSlide.warningResId, () -> {
                        if (getString(R.string.onboarding_privatedns_title).equals(currentSlide.title.toString())) {
                            PreferenceManager.getDefaultSharedPreferences(this).edit().putBoolean("filter", false)
                                    .apply();
                        }
                        next.run();
                    });
                } else {
                    next.run();
                }
            } else {
                finishOnboarding();
            }
        });

        btnPrevious.setOnClickListener(v -> {
            if (viewPager.getCurrentItem() > 0) {
                viewPager.setCurrentItem(viewPager.getCurrentItem() - 1);
            }
        });

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateButtons(position);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSlides();
    }

    private void setupSlides() {
        if (slidesInitialized) {
            return;
        }

        slides.clear();

        // 1. Welcome
        slides.add(new Slide(
                R.string.onboarding_welcome_title,
                getText(R.string.onboarding_welcome_title),
                getText(R.string.onboarding_welcome_desc),
                R.drawable.ic_rocket2,
                null,
                null));

        // 2. VPN (Only if not already prepared)
        boolean vpnPrepared = VpnService.prepare(this) == null;
        if (!vpnPrepared) {
            slides.add(new Slide(
                    R.string.onboarding_vpn_title,
                    getText(R.string.onboarding_vpn_title),
                    getText(R.string.onboarding_vpn_desc),
                    R.drawable.lockdown,
                    getString(R.string.onboarding_vpn_action),
                    R.string.onboarding_vpn_sure,
                    null)); // Listener set in refreshSlides
        }

        // 4. Notifications (Android 13+)
        if (Build.VERSION.SDK_INT >= 33) {
            boolean canNotify = Util.canNotify(this);
            if (!canNotify) {
                slides.add(new Slide(
                        R.string.onboarding_notify_title,
                        getText(R.string.onboarding_notify_title),
                        getText(R.string.onboarding_notify_desc),
                        R.drawable.twotone_notifications_24,
                        getString(R.string.onboarding_notify_action),
                        R.string.onboarding_notify_sure,
                        null));
            }
        }

        // 5. Battery Optimization
        boolean batteryOptimized = Util.batteryOptimizing(this);
        if (batteryOptimized) {
            slides.add(new Slide(
                    R.string.onboarding_battery_title,
                    getText(R.string.onboarding_battery_title),
                    getText(R.string.onboarding_battery_desc),
                    R.drawable.ic_scan_code,
                    getString(R.string.onboarding_battery_action),
                    R.string.onboarding_battery_sure,
                    null));
        }

        // 6. Unrestricted Network (Optional but recommended)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            boolean dataSaving = Util.dataSaving(this);
            if (dataSaving) {
                slides.add(new Slide(
                        R.string.onboarding_network_title,
                        getText(R.string.onboarding_network_title),
                        getText(R.string.onboarding_network_desc),
                        R.drawable.wifi,
                        getString(R.string.onboarding_network_action),
                        R.string.onboarding_network_sure,
                        null));
            }
        }

        // 7. Private DNS (Mandatory)
        boolean privateDnsEnabled = Util.isPrivateDns(this);
        if (privateDnsEnabled) {
            slides.add(new Slide(
                    R.string.onboarding_privatedns_title,
                    getText(R.string.onboarding_privatedns_title),
                    android.text.TextUtils.concat(getText(R.string.onboarding_privatedns_desc), "\n\n",
                            android.text.Html
                                    .fromHtml("<b>" + getString(R.string.onboarding_privatedns_instruction) + "</b>")),
                    R.drawable.screen,
                    getString(R.string.onboarding_privatedns_action),
                    R.string.onboarding_privatedns_skip_msg,
                    null));
        }

        // 8. Secure DNS
        final SharedPreferences prefsDns = PreferenceManager.getDefaultSharedPreferences(this);
        boolean dohEnabled = prefsDns.getBoolean("doh_enabled", false);
        if (!dohEnabled) {
            slides.add(new Slide(
                    R.string.onboarding_dns_title,
                    getText(R.string.onboarding_dns_title),
                    getText(R.string.onboarding_dns_desc),
                    R.drawable.lockdown,
                    getString(R.string.onboarding_dns_action),
                    null));
        }

        slidesInitialized = true;

        if (adapter == null) {
            adapter = new OnboardingAdapter(slides);
            viewPager.setAdapter(adapter);
        } else {
            adapter.notifyDataSetChanged();
        }

        updateButtons(viewPager.getCurrentItem());
    }

    private void refreshSlides() {
        if (!slidesInitialized) {
            setupSlides();
            return;
        }

        for (Slide slide : slides) {
            // 2. VPN
            if (slide.titleResId == R.string.onboarding_vpn_title) {
                boolean vpnPrepared = VpnService.prepare(this) == null;
                slide.actionButtonText = vpnPrepared ? getString(R.string.onboarding_action_granted)
                        : getString(R.string.onboarding_vpn_action);
                slide.warningResId = vpnPrepared ? 0 : R.string.onboarding_vpn_sure;
                slide.actionListener = v -> {
                    if (!vpnPrepared) {
                        Intent intent = VpnService.prepare(ActivityOnboarding.this);
                        if (intent != null) {
                            startActivityForResult(intent, 0);
                        }
                    }
                };
            }

            // 4. Notifications
            if (slide.titleResId == R.string.onboarding_notify_title) {
                boolean canNotify = Util.canNotify(this);
                slide.actionButtonText = canNotify ? getString(R.string.onboarding_action_granted)
                        : getString(R.string.onboarding_notify_action);
                slide.warningResId = canNotify ? 0 : R.string.onboarding_notify_sure;
                slide.actionListener = v -> {
                    if (!canNotify) {
                        ActivityCompat.requestPermissions(ActivityOnboarding.this,
                                new String[] { Manifest.permission.POST_NOTIFICATIONS }, 1);
                    }
                };
            }

            // 5. Battery Optimization
            if (slide.titleResId == R.string.onboarding_battery_title) {
                boolean batteryOptimized = Util.batteryOptimizing(this);
                slide.actionButtonText = batteryOptimized ? getString(R.string.onboarding_battery_action)
                        : getString(R.string.onboarding_action_unrestricted);
                slide.warningResId = batteryOptimized ? R.string.onboarding_battery_sure : 0;
                slide.actionListener = v -> {
                    if (batteryOptimized) {
                        try {
                            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                    .setData(Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                        } catch (Throwable ex) {
                            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                        }
                    }
                };
            }

            // 6. Unrestricted Network
            if (slide.titleResId == R.string.onboarding_network_title) {
                boolean dataSaving = Util.dataSaving(this);
                slide.actionButtonText = dataSaving ? getString(R.string.onboarding_network_action)
                        : getString(R.string.onboarding_action_unrestricted);
                slide.warningResId = dataSaving ? R.string.onboarding_network_sure : 0;
                slide.actionListener = v -> {
                    if (dataSaving) {
                        Intent intent = new Intent(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS,
                                Uri.parse("package:" + getPackageName()));
                        if (intent.resolveActivity(getPackageManager()) != null) {
                            startActivity(intent);
                        }
                    }
                };
            }

            // 7. Private DNS
            if (slide.titleResId == R.string.onboarding_privatedns_title) {
                boolean privateDnsEnabled = Util.isPrivateDns(this);
                slide.actionButtonText = privateDnsEnabled ? getString(R.string.onboarding_privatedns_action)
                        : getString(R.string.onboarding_action_disabled);
                slide.warningResId = privateDnsEnabled ? R.string.onboarding_privatedns_skip_msg : 0;
                slide.actionListener = v -> {
                    if (privateDnsEnabled) {
                        Intent intent = new Intent(Settings.ACTION_WIRELESS_SETTINGS);
                        if (intent.resolveActivity(getPackageManager()) == null) {
                            intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
                        }
                        startActivity(intent);
                    }
                };
            }

            // 8. Secure DNS
            if (slide.titleResId == R.string.onboarding_dns_title) {
                final SharedPreferences prefsDns = PreferenceManager.getDefaultSharedPreferences(this);
                boolean dohEnabled = prefsDns.getBoolean("doh_enabled", false);
                slide.actionButtonText = dohEnabled ? getString(R.string.onboarding_dns_action_disable)
                        : getString(R.string.onboarding_dns_action);
                slide.actionListener = v -> {
                    prefsDns.edit().putBoolean("doh_enabled", !dohEnabled).apply();
                    refreshSlides(); // Refresh status
                };
            }
        }

        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        updateButtons(viewPager.getCurrentItem());
    }

    private void updateButtons(int position) {
        btnNext.setEnabled(true);
        btnNext.setAlpha(1.0f);
        viewPager.setUserInputEnabled(true);

        if (position == 0) {
            btnPrevious.setVisibility(View.INVISIBLE);
        } else {
            btnPrevious.setVisibility(View.VISIBLE);
        }

        if (position == adapter.getItemCount() - 1) {
            btnNext.setText(R.string.title_finish);
        } else {
            btnNext.setText(R.string.title_next);
        }
    }

    private void finishOnboarding() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.edit()
                .putBoolean("onboarding_complete", true)
                .putBoolean("enabled", true)
                .apply();

        ServiceSinkhole.start("onboarding", this);

        startActivity(new Intent(this, ActivityMain.class));
        finish();
    }

    private static class Slide {
        int titleResId;
        CharSequence title;
        CharSequence desc;
        int iconResId;
        String actionButtonText;
        int warningResId;
        View.OnClickListener actionListener;

        Slide(int titleResId, CharSequence title, CharSequence desc, int iconResId, String actionButtonText,
                View.OnClickListener actionListener) {
            this(titleResId, title, desc, iconResId, actionButtonText, 0, actionListener);
        }

        Slide(int titleResId, CharSequence title, CharSequence desc, int iconResId, String actionButtonText,
                int warningResId, View.OnClickListener actionListener) {
            this.titleResId = titleResId;
            this.title = title;
            this.desc = desc;
            this.iconResId = iconResId;
            this.actionButtonText = actionButtonText;
            this.warningResId = warningResId;
            this.actionListener = actionListener;
        }
    }

    private class OnboardingAdapter extends RecyclerView.Adapter<OnboardingAdapter.SlideViewHolder> {
        private List<Slide> slides;

        OnboardingAdapter(List<Slide> slides) {
            this.slides = slides;
        }

        @NonNull
        @Override
        public SlideViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_onboarding, parent, false);
            return new SlideViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull SlideViewHolder holder, int position) {
            Slide slide = slides.get(position);
            holder.tvTitle.setText(slide.title);
            holder.tvDescription.setText(slide.desc);
            holder.ivIcon.setImageResource(slide.iconResId);

            if (slide.actionButtonText != null) {
                holder.btnAction.setVisibility(View.VISIBLE);
                holder.btnAction.setText(slide.actionButtonText);
                holder.btnAction.setOnClickListener(slide.actionListener);
            } else {
                holder.btnAction.setVisibility(View.GONE);
            }
        }

        @Override
        public int getItemCount() {
            return slides.size();
        }

        class SlideViewHolder extends RecyclerView.ViewHolder {
            TextView tvTitle, tvDescription;
            Button btnAction;
            android.widget.ImageView ivIcon;

            SlideViewHolder(@NonNull View itemView) {
                super(itemView);
                tvTitle = itemView.findViewById(R.id.tvTitle);
                tvDescription = itemView.findViewById(R.id.tvDescription);
                btnAction = itemView.findViewById(R.id.btnAction);
                ivIcon = itemView.findViewById(R.id.ivIcon);
            }
        }
    }
}
