package net.kollnig.missioncontrol;

import android.os.Bundle;
import android.text.InputType;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.compose.ui.platform.ComposeView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import net.kollnig.missioncontrol.data.Blocklist;
import net.kollnig.missioncontrol.data.BlocklistManager;
import net.kollnig.missioncontrol.ui.compose.BlocklistRow;
import net.kollnig.missioncontrol.ui.compose.BlocklistsScreen;
import net.kollnig.missioncontrol.ui.compose.BlocklistsScreenCallbacks;
import net.kollnig.missioncontrol.ui.compose.BlocklistsScreenController;
import net.kollnig.missioncontrol.ui.compose.BlocklistsScreenModel;

import java.net.MalformedURLException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import eu.faircode.netguard.ServiceSinkhole;
import eu.faircode.netguard.Util;

public class ActivityBlocklists extends AppCompatActivity {
    private BlocklistManager manager;
    private BlocklistsScreenController screenController;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Util.setTheme(this);
        super.onCreate(savedInstanceState);

        ComposeView composeView = new ComposeView(this);
        // A stable ID gives Compose's saveable state registry a key to persist
        // under, so the LazyColumn scroll position survives recreation.
        composeView.setId(R.id.compose_blocklists);
        setContentView(composeView);

        getSupportActionBar().setTitle(R.string.title_blocklists);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        manager = BlocklistManager.getInstance(this);
        manager.migrateIfNeeded();

        screenController = BlocklistsScreen.install(
                composeView,
                buildScreenModel(),
                new BlocklistsScreenCallbacks() {
                    @Override
                    public void onAdd() {
                        showAddDialog(null);
                    }

                    @Override
                    public void onEdit(String uuid) {
                        showAddDialog(uuid);
                    }

                    @Override
                    public void onEnabledChanged(String uuid, boolean enabled) {
                        updateEnabled(uuid, enabled);
                    }

                    @Override
                    public void onDelete(String uuid) {
                        showDeleteDialog(uuid);
                    }
                });
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private BlocklistsScreenModel buildScreenModel() {
        List<BlocklistRow> rows = new ArrayList<>();
        for (Blocklist item : manager.getBlocklists()) {
            String lastUpdate = null;
            if (item.lastModified > 0) {
                String last = SimpleDateFormat.getDateTimeInstance().format(new Date(item.lastModified));
                lastUpdate = getString(R.string.msg_last_update, last);
            }

            String error = item.lastDownloadSuccess ? null : item.lastErrorMessage;
            rows.add(new BlocklistRow(
                    item.uuid,
                    item.url,
                    lastUpdate,
                    error,
                    item.enabled,
                    getString(R.string.blocklist_enable_description, item.url),
                    getString(R.string.blocklist_delete_description)));
        }
        return new BlocklistsScreenModel(rows);
    }

    private void refreshScreen() {
        screenController.update(buildScreenModel());
    }

    private Blocklist findBlocklist(String uuid) {
        for (Blocklist item : manager.getBlocklists()) {
            if (item.uuid.equals(uuid))
                return item;
        }
        return null;
    }

    private void showAddDialog(String uuid) {
        String initialUrl = null;
        if (uuid != null) {
            Blocklist item = findBlocklist(uuid);
            if (item == null)
                return;
            initialUrl = item.url;
        }

        AlertDialog.Builder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle(uuid == null ? R.string.title_add_blocklist : R.string.title_blocklists);

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("https://example.com/hosts.txt");
        if (initialUrl != null)
            input.setText(initialUrl);
        builder.setView(input);

        builder.setPositiveButton(android.R.string.ok, (dialog, which) -> {
            String url = input.getText().toString().trim();
            if (!url.isEmpty()) {
                try {
                    URL parsed = new URL(url); // Validate URL
                    if (!"https".equalsIgnoreCase(parsed.getProtocol()))
                        throw new MalformedURLException("Only HTTPS blocklist URLs are supported");

                    Blocklist item = uuid == null ? null : findBlocklist(uuid);
                    if (uuid != null && item == null)
                        return;

                    if (item == null) {
                        Blocklist newItem = new Blocklist(url, true);
                        manager.addBlocklist(newItem);
                    } else {
                        if (!url.equals(item.url)) {
                            item.lastModified = 0;
                            item.lastDownloadSuccess = true;
                            item.lastErrorMessage = null;
                            manager.getBlocklistFile(item.uuid).delete();
                        }
                        item.url = url;
                        manager.updateBlocklist(item);
                    }
                    applyBlocklists();
                    refreshScreen();
                } catch (MalformedURLException e) {
                    Toast.makeText(ActivityBlocklists.this, R.string.msg_invalid_url, Toast.LENGTH_SHORT).show();
                }
            }
        });
        builder.setNegativeButton(android.R.string.cancel, (dialog, which) -> dialog.cancel());

        builder.show();
    }

    private void updateEnabled(String uuid, boolean enabled) {
        Blocklist item = findBlocklist(uuid);
        if (item == null)
            return;

        item.enabled = enabled;
        manager.updateBlocklist(item);
        applyBlocklists();
        refreshScreen();
    }

    private void showDeleteDialog(String uuid) {
        if (findBlocklist(uuid) == null)
            return;

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.title_delete_blocklist)
                .setMessage(R.string.msg_delete_blocklist_confirm)
                .setPositiveButton(android.R.string.yes, (dialog, which) -> {
                    Blocklist item = findBlocklist(uuid);
                    if (item == null)
                        return;
                    manager.removeBlocklist(item.uuid);
                    applyBlocklists();
                    refreshScreen();
                })
                .setNegativeButton(android.R.string.no, null)
                .show();
    }

    private void applyBlocklists() {
        if (!manager.mergeBlocklists()) {
            Toast.makeText(this, R.string.msg_apply_blocklists_failed, Toast.LENGTH_SHORT).show();
            return;
        }

        ServiceSinkhole.reload("blocklist changed", this, false);
    }
}
