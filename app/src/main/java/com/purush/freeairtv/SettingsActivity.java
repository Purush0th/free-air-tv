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
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Settings in two panes, 30/70. Left: each language under Channels, with a tick for the whole
 * language, then Updates and About. Right, in smaller type: what the focused left item holds:
 * Enable All, Disable All and that language's channels to tick one by one; the two update
 * checks with when each last ran; or the app's details.
 *
 * <p>Up/Down move in a pane, Right enters the right pane, Left returns, OK toggles or acts.
 */
public class SettingsActivity extends Activity {

    /** Start installing the available update as soon as the screen opens. */
    static final String EXTRA_INSTALL_UPDATE = "install_update";

    /** One row in either pane. */
    private static final class Row {
        static final int HEADING = 0;
        static final int LANGUAGE = 1;
        static final int UPDATES = 2;
        static final int CHANNEL_LIST = 3;
        static final int ABOUT = 4;
        static final int CHANNEL = 5;
        static final int INFO = 6;
        static final int ENABLE_ALL = 7;
        static final int DISABLE_ALL = 8;

        final int kind;
        final String text;
        final String lang;
        final Channel channel;
        final String value;

        Row(int kind, String text, String lang, Channel channel, String value) {
            this.kind = kind;
            this.text = text;
            this.lang = lang;
            this.channel = channel;
            this.value = value;
        }

        static Row heading(String text) {
            return new Row(HEADING, text, null, null, null);
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private final List<Row> navRows = new ArrayList<>();
    private final List<Row> detailRows = new ArrayList<>();

    private ChannelStore store;
    private Updater updater;
    private ListView nav;
    private ListView detail;
    private RowAdapter navAdapter;
    private RowAdapter detailAdapter;
    private TextView detailTitle;
    private TextView detailSub;
    /** The left-pane row whose contents the right pane shows. */
    private int activeNav = -1;
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
        nav = findViewById(R.id.nav);
        detail = findViewById(R.id.detail);
        detailTitle = findViewById(R.id.detail_title);
        detailSub = findViewById(R.id.detail_sub);
        navAdapter = new RowAdapter(navRows, true);
        detailAdapter = new RowAdapter(detailRows, false);
        nav.setAdapter(navAdapter);
        detail.setAdapter(detailAdapter);

        nav.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position != activeNav) {
                    activeNav = position;
                    buildDetail();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        nav.setOnItemClickListener((parent, view, position, id) -> onNavClicked(navRows.get(position)));
        detail.setOnItemClickListener((parent, view, position, id) -> onDetailClicked(detailRows.get(position)));
        nav.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && event.getAction() == KeyEvent.ACTION_DOWN) {
                return enterDetail();
            }
            return false;
        });
        detail.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && event.getAction() == KeyEvent.ACTION_DOWN) {
                nav.requestFocus();
                return true;
            }
            return false;
        });
        // The grey "you are here" pill on the left shows only while focus is on the right.
        View.OnFocusChangeListener refresh = (v, hasFocus) -> {
            navAdapter.notifyDataSetChanged();
            detailAdapter.notifyDataSetChanged();
        };
        nav.setOnFocusChangeListener(refresh);
        detail.setOnFocusChangeListener(refresh);

        store.load();
        buildNav();
        nav.requestFocus();
        if (getIntent().getBooleanExtra(EXTRA_INSTALL_UPDATE, false) && updater.available() != null) {
            selectNav(Row.UPDATES);
            installUpdate(updater.available());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        background.shutdownNow();
    }

    // ---- Panes ----------------------------------------------------------------------

    private void buildNav() {
        navRows.clear();
        navRows.add(Row.heading(getString(R.string.settings_languages)));
        for (String lang : store.languages()) {
            navRows.add(new Row(Row.LANGUAGE, null, lang, null, null));
        }
        navRows.add(Row.heading(getString(R.string.settings_app)));
        navRows.add(new Row(Row.UPDATES, getString(R.string.settings_updates), null, null, null));
        navRows.add(new Row(Row.ABOUT, getString(R.string.settings_about), null, null, null));
        navAdapter.notifyDataSetChanged();
        if (activeNav < 0 || activeNav >= navRows.size() || navRows.get(activeNav).kind == Row.HEADING) {
            activeNav = 1;
            nav.setSelection(activeNav);
        }
        buildDetail();
    }

    private void selectNav(int kind) {
        for (int i = 0; i < navRows.size(); i++) {
            if (navRows.get(i).kind == kind) {
                activeNav = i;
                nav.setSelection(i);
                buildDetail();
                return;
            }
        }
    }

    /** Fills the right pane for the active left item. */
    private void buildDetail() {
        detailRows.clear();
        Row item = navRows.get(activeNav);
        switch (item.kind) {
            case Row.LANGUAGE: {
                List<Channel> channels = store.channelsIn(item.lang);
                detailTitle.setText(languageTitle(item.lang));
                if (store.isLanguageHidden(item.lang)) {
                    detailSub.setText(getString(R.string.lang_detail_off, englishName(item.lang)));
                } else {
                    detailSub.setText(getString(R.string.lang_detail_on, shownCount(channels), channels.size()));
                }
                detailRows.add(new Row(Row.ENABLE_ALL, getString(R.string.enable_all), item.lang, null, null));
                detailRows.add(new Row(Row.DISABLE_ALL, getString(R.string.disable_all), item.lang, null, null));
                for (Channel ch : channels) {
                    detailRows.add(new Row(Row.CHANNEL, null, item.lang, ch, null));
                }
                break;
            }
            case Row.UPDATES:
                detailTitle.setText(R.string.settings_updates);
                detailSub.setText(null);
                detailRows.add(new Row(Row.UPDATES, null, null, null, null));
                detailRows.add(new Row(Row.CHANNEL_LIST, null, null, null, null));
                break;
            case Row.ABOUT: {
                detailTitle.setText(R.string.settings_about);
                detailSub.setText(null);
                int langs = store.languages().size();
                int total = store.all().size();
                detailRows.add(new Row(Row.INFO, getString(R.string.about_version), null, null,
                        BuildInfo.versionName(this)));
                detailRows.add(new Row(Row.INFO, getString(R.string.about_channels), null, null,
                        langs == 1 ? getString(R.string.about_channels_value_one, total)
                                : getString(R.string.about_channels_value, total, langs)));
                detailRows.add(new Row(Row.INFO, getString(R.string.about_source), null, null,
                        "github.com/" + getString(R.string.github_repo)));
                break;
            }
            default:
                break;
        }
        detailSub.setVisibility(detailSub.getText().length() == 0 ? View.GONE : View.VISIBLE);
        detailAdapter.notifyDataSetChanged();
    }

    /** Moves focus into the right pane, onto its first row that can be acted on. */
    private boolean enterDetail() {
        for (int i = 0; i < detailRows.size(); i++) {
            if (detailAdapter.isEnabled(i)) {
                detail.requestFocus();
                detail.setSelection(i);
                return true;
            }
        }
        return true; // nothing to act on there; keep focus here
    }

    private void onNavClicked(Row row) {
        switch (row.kind) {
            case Row.LANGUAGE:
                store.setLanguageHidden(row.lang, !store.isLanguageHidden(row.lang));
                navAdapter.notifyDataSetChanged();
                buildDetail();
                break;
            case Row.UPDATES:
                enterDetail();
                break;
            default:
                break;
        }
    }

    private void onDetailClicked(Row row) {
        switch (row.kind) {
            case Row.CHANNEL:
                store.setHidden(row.channel, !store.isHidden(row.channel));
                languageChanged(row.lang);
                break;
            case Row.ENABLE_ALL:
                store.setLanguageHidden(row.lang, false);
                store.setHidden(store.channelsIn(row.lang), false);
                languageChanged(row.lang);
                break;
            case Row.DISABLE_ALL:
                store.setHidden(store.channelsIn(row.lang), true);
                languageChanged(row.lang);
                break;
            case Row.UPDATES:
                Updater.Release r = updater.available();
                if (r != null) {
                    installUpdate(r);
                } else {
                    checkForUpdate();
                }
                break;
            case Row.CHANNEL_LIST:
                refreshChannelList();
                break;
            default:
                break;
        }
    }

    /** Refreshes both panes after a language's ticks change, keeping the focused row. */
    private void languageChanged(String lang) {
        navAdapter.notifyDataSetChanged();
        List<Channel> channels = store.channelsIn(lang);
        detailSub.setText(store.isLanguageHidden(lang)
                ? getString(R.string.lang_detail_off, englishName(lang))
                : getString(R.string.lang_detail_on, shownCount(channels), channels.size()));
        detailAdapter.notifyDataSetChanged();
    }

    // ---- Languages ------------------------------------------------------------------

    private static String englishName(String lang) {
        Locale l = Locale.forLanguageTag(lang);
        String name = l.getDisplayLanguage(Locale.ENGLISH);
        return name.isEmpty() ? lang : name;
    }

    private static String nativeName(String lang) {
        Locale l = Locale.forLanguageTag(lang);
        return l.getDisplayLanguage(l);
    }

    private static String languageTitle(String lang) {
        String en = englishName(lang);
        String own = nativeName(lang);
        return own.isEmpty() || own.equalsIgnoreCase(en) ? en : en + "  ·  " + own;
    }

    private int shownCount(List<Channel> channels) {
        int n = 0;
        for (Channel ch : channels) {
            if (!store.isHidden(ch)) {
                n++;
            }
        }
        return n;
    }

    // ---- Actions --------------------------------------------------------------------

    private void checkForUpdate() {
        if (busy) {
            return;
        }
        busy = true;
        updateStatus = getString(R.string.update_checking);
        detailAdapter.notifyDataSetChanged();
        background.execute(() -> {
            Updater.Release r = updater.check();
            handler.post(() -> {
                busy = false;
                updateStatus = r == null ? getString(R.string.update_none) : null;
                buildDetail();
            });
        });
    }

    private void installUpdate(Updater.Release release) {
        if (busy) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            updateStatus = getString(R.string.update_need_permission);
            detailAdapter.notifyDataSetChanged();
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
        detailAdapter.notifyDataSetChanged();
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
                detailAdapter.notifyDataSetChanged();
            });
        });
    }

    private void refreshChannelList() {
        if (busy) {
            return;
        }
        if (store.source() == ChannelStore.Source.ADB_OVERRIDE) {
            refreshStatus = getString(R.string.refresh_override);
            detailAdapter.notifyDataSetChanged();
            return;
        }
        busy = true;
        refreshStatus = getString(R.string.refresh_checking);
        detailAdapter.notifyDataSetChanged();
        background.execute(() -> {
            boolean changed = store.refreshFromGitHub();
            handler.post(() -> {
                busy = false;
                refreshStatus = getString(changed ? R.string.refresh_changed : R.string.refresh_same);
                store.load();
                buildNav();
            });
        });
    }

    // ---- Rows -----------------------------------------------------------------------

    private String sourceText() {
        switch (store.source()) {
            case ADB_OVERRIDE: return getString(R.string.source_override);
            case GITHUB: return getString(R.string.source_github, ago(store.remoteFetchedAt()));
            default: return getString(R.string.source_builtin);
        }
    }

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
        private final List<Row> rows;
        private final boolean isNav;

        RowAdapter(List<Row> rows, boolean isNav) {
            this.rows = rows;
            this.isNav = isNav;
        }

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
            Row row = rows.get(position);
            switch (row.kind) {
                case Row.HEADING:
                case Row.INFO:
                    return false;
                case Row.CHANNEL:
                case Row.DISABLE_ALL:
                    return !store.isLanguageHidden(row.lang);
                default:
                    return true;
            }
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
                    : inflater.inflate(isNav ? R.layout.item_setting : R.layout.item_setting_detail, parent, false);
            TextView title = v.findViewById(R.id.title);
            TextView sub = v.findViewById(R.id.sub);
            TextView value = v.findViewById(R.id.value);
            ImageView check = v.findViewById(R.id.check);
            value.setVisibility(View.GONE);
            value.setTextColor(getColorStateList(R.color.row_value));
            check.setVisibility(View.GONE);
            sub.setVisibility(View.GONE);
            v.setAlpha(1f);
            v.setContentDescription(null);
            v.setActivated(isNav && position == activeNav && !nav.hasFocus());
            switch (row.kind) {
                case Row.LANGUAGE: {
                    boolean off = store.isLanguageHidden(row.lang);
                    List<Channel> channels = store.channelsIn(row.lang);
                    title.setText(englishName(row.lang));
                    sub.setText(getString(R.string.lang_sub, nativeName(row.lang),
                            off ? 0 : shownCount(channels), channels.size()));
                    sub.setVisibility(View.VISIBLE);
                    check.setImageResource(off ? R.drawable.check_off : R.drawable.check_on);
                    check.setVisibility(View.VISIBLE);
                    v.setContentDescription(englishName(row.lang) + ", "
                            + getString(off ? R.string.hidden : R.string.shown));
                    break;
                }
                case Row.UPDATES: {
                    Updater.Release r = updater.available();
                    String version = BuildInfo.versionName(SettingsActivity.this);
                    if (isNav) {
                        title.setText(row.text);
                        sub.setText(getString(R.string.updates_sub, version));
                    } else {
                        title.setText(r != null ? getString(R.string.update_install, r.version)
                                : getString(R.string.update_check));
                        sub.setText(updateStatus != null ? updateStatus
                                : getString(R.string.update_status, version, ago(updater.checkedAt())));
                    }
                    sub.setVisibility(View.VISIBLE);
                    if (r != null) {
                        value.setText(R.string.update_new);
                        value.setVisibility(View.VISIBLE);
                    }
                    break;
                }
                case Row.CHANNEL_LIST:
                    title.setText(isNav ? row.text : getString(R.string.refresh));
                    sub.setText(!isNav && refreshStatus != null ? refreshStatus : sourceText());
                    sub.setVisibility(View.VISIBLE);
                    break;
                case Row.ABOUT:
                case Row.ENABLE_ALL:
                    title.setText(row.text);
                    break;
                case Row.DISABLE_ALL:
                    title.setText(row.text);
                    v.setAlpha(store.isLanguageHidden(row.lang) ? 0.4f : 1f);
                    break;
                case Row.CHANNEL: {
                    boolean hidden = store.isHidden(row.channel);
                    title.setText(row.channel.name);
                    sub.setText(row.channel.locale);
                    sub.setVisibility(row.channel.locale.isEmpty() ? View.GONE : View.VISIBLE);
                    check.setImageResource(hidden ? R.drawable.check_off : R.drawable.check_on);
                    check.setVisibility(View.VISIBLE);
                    // A switched-off language greys its channels; they keep their own ticks.
                    v.setAlpha(store.isLanguageHidden(row.lang) ? 0.4f : 1f);
                    v.setContentDescription(row.channel.name + ", "
                            + getString(hidden ? R.string.hidden : R.string.shown));
                    break;
                }
                case Row.INFO:
                    title.setText(row.text);
                    value.setText(row.value);
                    value.setTextColor(getColor(R.color.muted));
                    value.setVisibility(View.VISIBLE);
                    break;
                default:
                    break;
            }
            return v;
        }
    }
}
