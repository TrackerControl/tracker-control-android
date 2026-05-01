package net.kollnig.missioncontrol;

import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import net.kollnig.missioncontrol.wg.WgProfileManager;
import net.kollnig.missioncontrol.wg.WgConfigParser;

import org.json.JSONException;

import java.util.List;

import eu.faircode.netguard.ServiceSinkhole;
import eu.faircode.netguard.Util;

public class ActivityWireGuardProfiles extends AppCompatActivity {
    private WgProfileManager manager;
    private ProfileAdapter adapter;
    private TextView empty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Util.setTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wg_profiles);

        getSupportActionBar().setTitle(R.string.setting_wg_profile_manage);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        manager = new WgProfileManager(this);
        manager.migrateIfNeeded();

        empty = findViewById(R.id.empty);
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ProfileAdapter(this);
        list.setAdapter(adapter);

        FloatingActionButton fab = findViewById(R.id.fab);
        fab.setOnClickListener(v -> showProfileDialog(null));

        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void refresh() {
        adapter.refresh();
        empty.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
    }

    private void showProfileDialog(WgProfileManager.Profile item) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        form.setPadding(pad, pad / 2, pad, 0);

        final EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint(R.string.msg_wg_profile_name);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        if (item != null)
            name.setText(item.name);
        form.addView(name, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final EditText config = new EditText(this);
        config.setMinLines(10);
        config.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        config.setHint(R.string.msg_wg_profile_config_hint);
        config.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE |
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        if (item != null)
            config.setText(item.config);
        form.addView(config, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle(item == null ? R.string.setting_wg_profile_save : R.string.setting_wg_profile)
                .setView(scroll)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null);

        boolean canActivate = item != null && !item.id.equals(manager.getActiveProfileId());
        if (canActivate)
            builder.setNeutralButton(R.string.msg_wg_profile_set_active, null);

        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String profileName = name.getText().toString().trim();
                String profileConfig = config.getText().toString().trim();
                if (TextUtils.isEmpty(profileName)) {
                    name.setError(getString(R.string.msg_wg_profile_name));
                    return;
                }
                if (TextUtils.isEmpty(profileConfig)) {
                    config.setError(getString(R.string.summary_wg_config));
                    return;
                }
                try {
                    WgConfigParser.INSTANCE.parse(profileConfig);
                    manager.saveProfile(item == null ? null : item.id, profileName, profileConfig);
                    applyProfiles();
                    refresh();
                    Toast.makeText(this, R.string.msg_wg_profile_saved, Toast.LENGTH_LONG).show();
                    dialog.dismiss();
                } catch (JSONException ex) {
                    Toast.makeText(this, ex.toString(), Toast.LENGTH_LONG).show();
                } catch (Throwable ex) {
                    Toast.makeText(this,
                            getString(R.string.msg_wg_config_invalid, ex.getMessage()),
                            Toast.LENGTH_LONG).show();
                }
            });

            if (canActivate)
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                    manager.setActiveProfile(item.id);
                    applyProfiles();
                    refresh();
                    dialog.dismiss();
                });
        });
        dialog.show();
    }

    private void confirmDelete(WgProfileManager.Profile item) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.setting_wg_profile_delete)
                .setMessage(R.string.msg_wg_profile_delete_confirm)
                .setPositiveButton(android.R.string.yes, (dialog, which) -> {
                    manager.deleteProfile(item.id);
                    applyProfiles();
                    refresh();
                    Toast.makeText(this, R.string.msg_wg_profile_deleted, Toast.LENGTH_LONG).show();
                })
                .setNegativeButton(android.R.string.no, null)
                .show();
    }

    private void applyProfiles() {
        ServiceSinkhole.reload("wireguard profile changed", this, false);
    }

    private class ProfileAdapter extends RecyclerView.Adapter<ProfileAdapter.ViewHolder> {
        private final Context context;
        private List<WgProfileManager.Profile> profiles;

        ProfileAdapter(Context context) {
            this.context = context;
            this.profiles = manager.getProfiles();
        }

        void refresh() {
            this.profiles = manager.getProfiles();
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_wg_profile, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            WgProfileManager.Profile item = profiles.get(position);
            boolean active = item.id.equals(manager.getActiveProfileId());
            String summary = manager.getProfileSummary(item);

            holder.textName.setText(item.name);
            holder.textSummary.setText(summary);
            holder.textSummary.setVisibility(TextUtils.isEmpty(summary) ? View.GONE : View.VISIBLE);
            holder.textActive.setVisibility(active ? View.VISIBLE : View.GONE);
            holder.itemView.setOnClickListener(v -> showProfileDialog(item));
            holder.btnDelete.setOnClickListener(v -> confirmDelete(item));
        }

        @Override
        public int getItemCount() {
            return profiles.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView textName;
            TextView textSummary;
            TextView textActive;
            ImageButton btnDelete;

            ViewHolder(View itemView) {
                super(itemView);
                textName = itemView.findViewById(R.id.textName);
                textSummary = itemView.findViewById(R.id.textSummary);
                textActive = itemView.findViewById(R.id.textActive);
                btnDelete = itemView.findViewById(R.id.btnDelete);
            }
        }
    }
}
