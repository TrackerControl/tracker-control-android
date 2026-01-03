package net.kollnig.missioncontrol;

import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import net.kollnig.missioncontrol.data.Blocklist;
import net.kollnig.missioncontrol.data.BlocklistManager;

import java.net.MalformedURLException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import eu.faircode.netguard.Util;

public class ActivityBlocklists extends AppCompatActivity {
    private BlocklistAdapter adapter;
    private BlocklistManager manager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Util.setTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_blocklists);

        getSupportActionBar().setTitle(R.string.title_blocklists);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        manager = BlocklistManager.getInstance(this);
        manager.migrateIfNeeded();

        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new BlocklistAdapter(this, manager);
        list.setAdapter(adapter);

        FloatingActionButton fab = findViewById(R.id.fab);
        fab.setOnClickListener(v -> showAddDialog(null));
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showAddDialog(Blocklist item) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(item == null ? R.string.title_add_blocklist : R.string.title_blocklists);

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("https://example.com/hosts.txt");
        if (item != null)
            input.setText(item.url);
        builder.setView(input);

        builder.setPositiveButton(android.R.string.ok, (dialog, which) -> {
            String url = input.getText().toString().trim();
            if (!url.isEmpty()) {
                try {
                    new URL(url); // Validate URL
                    if (item == null) {
                        Blocklist newItem = new Blocklist(url, true);
                        manager.addBlocklist(newItem);
                    } else {
                        item.url = url;
                        manager.updateBlocklist(item);
                    }
                    adapter.refresh();
                } catch (MalformedURLException e) {
                    Toast.makeText(ActivityBlocklists.this, R.string.msg_invalid_url, Toast.LENGTH_SHORT).show();
                }
            }
        });
        builder.setNegativeButton(android.R.string.cancel, (dialog, which) -> dialog.cancel());

        builder.show();
    }

    private class BlocklistAdapter extends RecyclerView.Adapter<BlocklistAdapter.ViewHolder> {
        private List<Blocklist> list;
        private Context context;
        private BlocklistManager manager;

        public BlocklistAdapter(Context context, BlocklistManager manager) {
            this.context = context;
            this.manager = manager;
            this.list = manager.getBlocklists();
        }

        public void refresh() {
            this.list = manager.getBlocklists();
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_blocklist, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            Blocklist item = list.get(position);
            holder.textUrl.setText(item.url);

            if (item.lastModified > 0) {
                String last = SimpleDateFormat.getDateTimeInstance().format(new Date(item.lastModified));
                holder.textLastUpdate.setText(context.getString(R.string.msg_last_update, last));
                holder.textLastUpdate.setVisibility(View.VISIBLE);
            } else {
                holder.textLastUpdate.setVisibility(View.GONE);
            }

            if (item.lastDownloadSuccess) {
                holder.textError.setVisibility(View.GONE);
            } else {
                holder.textError.setText(item.lastErrorMessage);
                holder.textError.setVisibility(View.VISIBLE);
            }

            holder.switchEnabled.setChecked(item.enabled);
            holder.switchEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
                item.enabled = isChecked;
                manager.updateBlocklist(item);
            });

            // Edit on click
            holder.itemView.setOnClickListener(v -> showAddDialog(item));

            holder.btnDelete.setOnClickListener(v -> {
                new AlertDialog.Builder(context)
                        .setTitle(R.string.title_delete_blocklist)
                        .setMessage(R.string.msg_delete_blocklist_confirm)
                        .setPositiveButton(android.R.string.yes, (dialog, which) -> {
                            manager.removeBlocklist(item.uuid);
                            refresh();
                        })
                        .setNegativeButton(android.R.string.no, null)
                        .show();
            });
        }

        @Override
        public int getItemCount() {
            return list.size();
        }

        public class ViewHolder extends RecyclerView.ViewHolder {
            TextView textUrl;
            TextView textLastUpdate;
            TextView textError;
            SwitchCompat switchEnabled;
            ImageButton btnDelete;

            public ViewHolder(View itemView) {
                super(itemView);
                textUrl = itemView.findViewById(R.id.textUrl);
                textLastUpdate = itemView.findViewById(R.id.textLastUpdate);
                textError = itemView.findViewById(R.id.textError);
                switchEnabled = itemView.findViewById(R.id.switchEnabled);
                btnDelete = itemView.findViewById(R.id.btnDelete);
            }
        }
    }
}
