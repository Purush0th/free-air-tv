package com.purush.freeairtv;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Settings: show or hide each channel, refresh the channel list, and install updates.
 * Everything is one D-pad list; OK acts on the focused row.
 */
public class SettingsActivity extends Activity {

    /** Start installing the available update as soon as the screen opens. */
    static final String EXTRA_INSTALL_UPDATE = "install_update";

    private static final Map<String, String> GROUP_TAMIL = new HashMap<>();
    static {
        GROUP_TAMIL.put("Devotional", "பக்தி");
        GROUP_TAMIL.put("General", "பொழுதுபோக்கு");
        GROUP_TAMIL.put("News", "செய்திகள்");
    }

    /** One list row: a heading, an action, or a channel switch. */
    private static final class Row {
        static final int HEADING = 0;
        static final int UPDATE = 1;
        static final int REFRESH = 2;
        static final int CHANNEL = 3;
        static final int ABOUT = 4;

        final int kind;
        final String text;
        final Channel channel;

        Row(int kind, String text, Channel channel) {
            this.kind = kind;
            this.text = text;
            this.channel = channel;
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private final List<Row> rows = new ArrayList<>();

    private ChannelStore store;
    private Updater updater;
    private RowAdapter adapter;
    private ListView list;
    /** Live status shown under the update and refresh rows while they work. */
    private String updateStatus;
    private String refreshStatus;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        store = new ChannelStore(this);
        updater = new Updater(this);
        list = findViewById(R.id.list);
        adapter = new RowAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> onRowClicked(rows.get(position)));
        store.load();
        buildRows();
        list.requestFocus();
        if (getIntent().getBooleanExtra(EXTRA_INSTALL_UPDATE, false) && updater.available() != null) {
            installUpdate(updater.available());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        background.shutdownNow();
    }

    private void buildRows() {
        rows.clear();
        rows.add(new Row(Row.HEADING, getString(R.string.settings_updates), null));
        rows.add(new Row(Row.UPDATE, null, null));
        rows.add(new Row(Row.HEADING, getString(R.string.settings_channel_list), null));
        rows.add(new Row(Row.REFRESH, null, null));
        String last = null;
        for (Channel ch : store.all()) {
            if (!ch.group.equals(last)) {
                String ta = GROUP_TAMIL.get(ch.group);
                String title = ch.group.isEmpty() ? getString(R.string.settings_channels)
                        : ta == null ? ch.group : ch.group + "  ·  " + ta;
                rows.add(new Row(Row.HEADING, title, null));
                last = ch.group;
            }
            rows.add(new Row(Row.CHANNEL, null, ch));
        }
        rows.add(new Row(Row.HEADING, getString(R.string.settings_about), null));
        rows.add(new Row(Row.ABOUT, null, null));
        adapter.notifyDataSetChanged();
    }

    private void onRowClicked(Row row) {
        switch (row.kind) {
            case Row.CHANNEL:
                store.setHidden(row.channel, !store.isHidden(row.channel));
                adapter.notifyDataSetChanged();
                break;
            case Row.UPDATE:
                Updater.Release r = updater.available();
                if (r != null) {
                    installUpdate(r);
                } else {
                    checkForUpdate();
                }
                break;
            case Row.REFRESH:
                refreshChannelList();
                break;
            default:
                break;
        }
    }

    // ---- Actions --------------------------------------------------------------------

    private void checkForUpdate() {
        if (busy) {
            return;
        }
        busy = true;
        updateStatus = getString(R.string.update_checking);
        adapter.notifyDataSetChanged();
        background.execute(() -> {
            Updater.Release r = updater.check();
            handler.post(() -> {
                busy = false;
                updateStatus = r == null ? getString(R.string.update_none) : null;
                adapter.notifyDataSetChanged();
            });
        });
    }

    private void installUpdate(Updater.Release release) {
        if (busy) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            updateStatus = getString(R.string.update_need_permission);
            adapter.notifyDataSetChanged();
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {
                // Fire OS may not have this screen; the message tells the user what to do.
            }
            return;
        }
        busy = true;
        updateStatus = getString(R.string.update_downloading, release.version);
        adapter.notifyDataSetChanged();
        background.execute(() -> {
            String error = null;
            try {
                updater.downloadAndInstall(release);
            } catch (Exception e) {
                error = e.getMessage();
            }
            String finalError = error;
            handler.post(() -> {
                busy = false;
                updateStatus = finalError == null ? getString(R.string.update_confirm)
                        : getString(R.string.update_failed, finalError);
                adapter.notifyDataSetChanged();
            });
        });
    }

    private void refreshChannelList() {
        if (busy) {
            return;
        }
        if (store.source() == ChannelStore.Source.ADB_OVERRIDE) {
            refreshStatus = getString(R.string.refresh_override);
            adapter.notifyDataSetChanged();
            return;
        }
        busy = true;
        refreshStatus = getString(R.string.refresh_checking);
        adapter.notifyDataSetChanged();
        background.execute(() -> {
            boolean changed = store.refreshFromGitHub();
            handler.post(() -> {
                busy = false;
                refreshStatus = getString(changed ? R.string.refresh_changed : R.string.refresh_same);
                store.load();
                buildRows();
            });
        });
    }

    // ---- Rows -----------------------------------------------------------------------

    private String ago(long time) {
        if (time == 0) {
            return getString(R.string.never);
        }
        if (System.currentTimeMillis() - time < DateUtils.MINUTE_IN_MILLIS) {
            return getString(R.string.just_now);
        }
        return DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS).toString();
    }

    private final class RowAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).kind == Row.HEADING ? 0 : 1;
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        @Override
        public boolean isEnabled(int position) {
            int kind = rows.get(position).kind;
            return kind != Row.HEADING && kind != Row.ABOUT;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Row row = rows.get(position);
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (row.kind == Row.HEADING) {
                TextView v = convertView != null ? (TextView) convertView
                        : (TextView) inflater.inflate(R.layout.item_group, parent, false);
                v.setText(row.text);
                return v;
            }
            View v = convertView != null ? convertView
                    : inflater.inflate(R.layout.item_setting, parent, false);
            TextView title = v.findViewById(R.id.title);
            TextView sub = v.findViewById(R.id.sub);
            TextView value = v.findViewById(R.id.value);
            value.setVisibility(View.GONE);
            sub.setVisibility(View.VISIBLE);
            String version = BuildInfo.versionName(SettingsActivity.this);
            switch (row.kind) {
                case Row.UPDATE: {
                    Updater.Release r = updater.available();
                    if (r != null) {
                        title.setText(getString(R.string.update_install, r.version));
                        value.setText(R.string.update_new);
                        value.setVisibility(View.VISIBLE);
                    } else {
                        title.setText(R.string.update_check);
                    }
                    sub.setText(updateStatus != null ? updateStatus
                            : getString(R.string.update_status, version, ago(updater.checkedAt())));
                    break;
                }
                case Row.REFRESH: {
                    title.setText(R.string.refresh);
                    String from;
                    switch (store.source()) {
                        case ADB_OVERRIDE: from = getString(R.string.source_override); break;
                        case GITHUB: from = getString(R.string.source_github, ago(store.remoteFetchedAt())); break;
                        default: from = getString(R.string.source_builtin); break;
                    }
                    sub.setText(refreshStatus != null ? refreshStatus : from);
                    break;
                }
                case Row.CHANNEL: {
                    boolean hidden = store.isHidden(row.channel);
                    title.setText(row.channel.name);
                    sub.setText(row.channel.tamil);
                    sub.setVisibility(row.channel.tamil.isEmpty() ? View.GONE : View.VISIBLE);
                    value.setText(hidden ? R.string.hidden : R.string.shown);
                    value.setEnabled(!hidden); // the colour list greys out "Hidden"
                    value.setVisibility(View.VISIBLE);
                    break;
                }
                case Row.ABOUT: {
                    title.setText(getString(R.string.about_title, version));
                    sub.setText(getString(R.string.about_sub, getString(R.string.github_repo)));
                    break;
                }
                default:
                    break;
            }
            return v;
        }
    }
}
