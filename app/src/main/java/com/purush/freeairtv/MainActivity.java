package com.purush.freeairtv;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.PlaybackException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * FreeAir TV: free-to-air channels played full screen.
 *
 * <p>Remote: Up/Down change channel (Up = next). OK or Left opens the channel list; Right
 * (or Menu) opens the player panel with quality, volume and mute. Back closes whichever
 * panel is open; in full screen, Back twice leaves the app.
 */
public class MainActivity extends Activity implements LivePlayer.Listener {

    private static final String TAG = "FreeAirTv";
    private static final long RETRY_DELAY_MS = 5_000;
    // Restart if buffering never recovers (live HLS sometimes stalls without an error).
    private static final long STALL_TIMEOUT_MS = 30_000;
    // Show "Connecting…" only if buffering lasts long enough to notice.
    private static final long CONNECTING_DELAY_MS = 700;
    private static final long BANNER_MS = 4_000;
    private static final long TOAST_MS = 2_000;
    private static final long EXIT_WINDOW_MS = 2_500;
    // After this many failed attempts in a row, suggest moving on.
    private static final int HINT_AFTER_FAILURES = 3;
    // How often to see whether the channel list or a release is due for a check.
    private static final long BACKGROUND_TICK_MS = 60L * 60 * 1000;
    private static final int VOLUME_STEP = 10;

    /** Guide row that is not a channel. */
    private static final int ROW_UPDATE = -1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private final Runnable retry = () -> play(this.current);
    private final Runnable stallWatchdog = () -> {
        this.failures++;
        play(this.current);
    };
    private final Runnable showConnecting = this::showConnecting;
    private final Runnable hideBanner = () -> this.banner.setVisibility(View.GONE);
    private final Runnable hideVolumeToast = () -> this.volumeToast.setVisibility(View.GONE);
    private final Runnable backgroundTick = this::checkInBackground;

    private ChannelStore store;
    private Updater updater;
    private LivePlayer player;
    private final List<Channel> channels = new ArrayList<>();
    private final List<Integer> rows = new ArrayList<>();
    private final List<OptionRow> options = new ArrayList<>();
    private Updater.Release update;
    private int current;
    private int failures;
    private long backPressedAt;

    private FrameLayout root;
    private SurfaceView surface;
    private TextView status;
    private View banner;
    private TextView bannerNum;
    private TextView bannerName;
    private TextView bannerTamil;
    private TextView bannerQuality;
    private TextView volumeToast;
    private View guide;
    private ListView list;
    private View optionsPanel;
    private ListView optionsList;
    private GuideAdapter guideAdapter;
    private OptionsAdapter optionsAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        store = new ChannelStore(this);
        updater = new Updater(this);
        root = (FrameLayout) surfaceParent();
        surface = findViewById(R.id.surface);
        status = findViewById(R.id.status);
        banner = findViewById(R.id.banner);
        bannerNum = findViewById(R.id.banner_num);
        bannerName = findViewById(R.id.banner_name);
        bannerTamil = findViewById(R.id.banner_tamil);
        bannerQuality = findViewById(R.id.banner_quality);
        volumeToast = findViewById(R.id.volume_toast);
        guide = findViewById(R.id.guide);
        list = findViewById(R.id.list);
        optionsPanel = findViewById(R.id.options);
        optionsList = findViewById(R.id.options_list);

        guideAdapter = new GuideAdapter();
        list.setAdapter(guideAdapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            int row = rows.get(position);
            if (row == ROW_UPDATE) {
                startActivity(new Intent(this, SettingsActivity.class)
                        .putExtra(SettingsActivity.EXTRA_INSTALL_UPDATE, true));
                return;
            }
            if (row != current || player.isPaused()) {
                failures = 0;
                play(row);
            }
            hideGuide();
        });
        findViewById(R.id.settings_button).setOnClickListener(
                v -> startActivity(new Intent(this, SettingsActivity.class)));

        // Draw the white highlight only while the list has focus; otherwise it would stay behind
        // a row whose text has turned white again (e.g. when focus moves up to Settings).
        list.setOnFocusChangeListener((v, has) -> list.setSelector(
                has ? R.drawable.list_selector : android.R.color.transparent));

        optionsAdapter = new OptionsAdapter();
        optionsList.setAdapter(optionsAdapter);
        optionsList.setOnItemClickListener((parent, view, position, id) -> onOptionClicked(options.get(position)));

        player = new LivePlayer(this, surface, this);
    }

    private View surfaceParent() {
        return ((ViewGroup) findViewById(android.R.id.content)).getChildAt(0);
    }

    @Override
    protected void onStart() {
        super.onStart();
        hideSystemBars();
        store.load();
        update = updater.available();
        reloadChannels(store.lastChannelId());
        if (channels.isEmpty()) {
            status.setText(R.string.no_channels);
            status.setVisibility(View.VISIBLE);
        } else {
            failures = 0;
            play(current);
        }
        showGuide();
        handler.post(backgroundTick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacksAndMessages(null);
        player.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        background.shutdownNow();
        player.release();
    }

    // ---- Channel list ---------------------------------------------------------------

    /** Rebuilds the guide from the store's visible channels, keeping {@code keepId} selected. */
    private void reloadChannels(String keepId) {
        channels.clear();
        channels.addAll(store.visible());
        current = 0;
        for (int i = 0; i < channels.size(); i++) {
            if (channels.get(i).id.equals(keepId)) {
                current = i;
            }
        }
        buildRows();
        guideAdapter.notifyDataSetChanged();
    }

    private void buildRows() {
        rows.clear();
        if (update != null) {
            rows.add(ROW_UPDATE);
        }
        for (int i = 0; i < channels.size(); i++) {
            rows.add(i);
        }
    }

    private int rowOf(int channel) {
        return Math.max(0, rows.indexOf(channel));
    }

    private String currentName() {
        return channels.isEmpty() ? "-" : channels.get(current).name;
    }

    /** Refreshes the channel list and looks for a release when each is due. */
    private void checkInBackground() {
        boolean listDue = store.source() != ChannelStore.Source.ADB_OVERRIDE && store.remoteDue();
        boolean releaseDue = updater.checkDue();
        if (listDue || releaseDue) {
            background.execute(() -> {
                boolean listChanged = listDue && store.refreshFromGitHub();
                Updater.Release found = releaseDue ? updater.check() : updater.available();
                handler.post(() -> onBackgroundResult(listChanged, found));
            });
        }
        handler.removeCallbacks(backgroundTick);
        handler.postDelayed(backgroundTick, BACKGROUND_TICK_MS);
    }

    private void onBackgroundResult(boolean listChanged, Updater.Release found) {
        boolean updateChanged = (found == null) != (update == null);
        update = found;
        if (listChanged) {
            String watching = channels.isEmpty() ? null : channels.get(current).id;
            String watchingUrl = channels.isEmpty() ? null : channels.get(current).url;
            store.load();
            reloadChannels(watching);
            // Keep playing unless the stream we are on has moved or gone.
            if (!channels.isEmpty() && !channels.get(current).url.equals(watchingUrl)) {
                failures = 0;
                play(current);
            }
        } else if (updateChanged) {
            buildRows();
            guideAdapter.notifyDataSetChanged();
        }
    }

    // ---- Playback -------------------------------------------------------------------

    private void play(int index) {
        if (channels.isEmpty()) {
            return;
        }
        current = (index % channels.size() + channels.size()) % channels.size();
        Channel ch = channels.get(current);
        store.setLastChannelId(ch.id);
        Log.i(TAG, "Playing " + ch.name + " " + ch.url);

        handler.removeCallbacks(retry);
        handler.removeCallbacks(showConnecting);
        if (failures == 0) {
            handler.postDelayed(showConnecting, CONNECTING_DELAY_MS);
        }
        player.play(ch.url);
        armWatchdog();
        list.setItemChecked(rowOf(current), true);
        guideAdapter.notifyDataSetChanged();
    }

    private void zap(int delta) {
        if (channels.isEmpty()) {
            return;
        }
        failures = 0;
        play(current + delta);
        showBanner();
    }

    private void armWatchdog() {
        handler.removeCallbacks(stallWatchdog);
        handler.postDelayed(stallWatchdog, STALL_TIMEOUT_MS);
    }

    private void showConnecting() {
        status.setText(getString(R.string.connecting, currentName()));
        status.setVisibility(View.VISIBLE);
    }

    @Override
    public void onBuffering() {
        handler.removeCallbacks(showConnecting);
        handler.postDelayed(showConnecting, CONNECTING_DELAY_MS);
        armWatchdog();
    }

    @Override
    public void onPlaying() {
        handler.removeCallbacks(stallWatchdog);
        handler.removeCallbacks(showConnecting);
        failures = 0;
        status.setVisibility(View.GONE);
    }

    @Override
    public void onError(PlaybackException error) {
        Log.w(TAG, "Playback failed on " + currentName(), error);
        handler.removeCallbacks(stallWatchdog);
        handler.removeCallbacks(showConnecting);
        failures++;
        int msg = failures >= HINT_AFTER_FAILURES ? R.string.retrying_hint : R.string.retrying;
        status.setText(getString(msg, currentName()));
        status.setVisibility(View.VISIBLE);
        handler.removeCallbacks(retry);
        handler.postDelayed(retry, RETRY_DELAY_MS);
    }

    /** Fits the picture to the screen at the stream's aspect ratio. */
    @Override
    public void onVideoSize(int width, int height) {
        int w = root.getWidth();
        int h = root.getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        float video = (float) width / height;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surface.getLayoutParams();
        if ((float) w / h > video) {
            lp.width = Math.round(h * video);
            lp.height = h;
        } else {
            lp.width = w;
            lp.height = Math.round(w / video);
        }
        surface.setLayoutParams(lp);
    }

    @Override
    public void onQualityChanged() {
        updateBannerQuality();
        if (optionsPanel.getVisibility() == View.VISIBLE) {
            buildOptions();
        }
    }

    private void updateBannerQuality() {
        int h = player.playingHeight();
        bannerQuality.setText(h > 0 ? getString(R.string.live_quality, h) : getString(R.string.live));
    }

    private void showBanner() {
        if (channels.isEmpty()) {
            return;
        }
        Channel ch = channels.get(current);
        bannerNum.setText(String.valueOf(current + 1));
        bannerName.setText(ch.name);
        bannerTamil.setText(ch.tamil);
        bannerTamil.setVisibility(ch.tamil.isEmpty() ? View.GONE : View.VISIBLE);
        updateBannerQuality();
        banner.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideBanner);
        handler.postDelayed(hideBanner, BANNER_MS);
    }

    private void showVolumeToast() {
        volumeToast.setText(player.muted() ? getString(R.string.muted)
                : getString(R.string.volume_value, player.volume()));
        volumeToast.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideVolumeToast);
        handler.postDelayed(hideVolumeToast, TOAST_MS);
    }

    // ---- Panels ---------------------------------------------------------------------

    private boolean guideVisible() {
        return guide.getVisibility() == View.VISIBLE;
    }

    private boolean optionsVisible() {
        return optionsPanel.getVisibility() == View.VISIBLE;
    }

    private void showGuide() {
        hideOptions();
        banner.setVisibility(View.GONE);
        guide.setVisibility(View.VISIBLE);
        list.requestFocus();
        list.setSelection(rowOf(current));
    }

    private void hideGuide() {
        guide.setVisibility(View.GONE);
        showBanner();
    }

    private void showOptions() {
        guide.setVisibility(View.GONE);
        banner.setVisibility(View.GONE);
        buildOptions();
        optionsPanel.setVisibility(View.VISIBLE);
        optionsList.requestFocus();
        // Start on the quality that is selected.
        for (int i = 0; i < options.size(); i++) {
            OptionRow r = options.get(i);
            if (r.kind == OptionRow.QUALITY && r.height == player.quality()) {
                optionsList.setSelection(i);
                break;
            }
        }
    }

    private void hideOptions() {
        optionsPanel.setVisibility(View.GONE);
    }

    @Override
    public void onBackPressed() {
        if (optionsVisible()) {
            hideOptions();
            return;
        }
        if (guideVisible() && !channels.isEmpty()) {
            hideGuide();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - backPressedAt < EXIT_WINDOW_MS || channels.isEmpty()) {
            super.onBackPressed();
        } else {
            backPressedAt = now;
            Toast.makeText(this, R.string.press_back_to_exit, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // In the player panel, Left/Right on the volume row change the volume.
        if (optionsVisible() && event.getAction() == KeyEvent.ACTION_DOWN) {
            int code = event.getKeyCode();
            Object sel = optionsList.getSelectedItem();
            if (sel instanceof OptionRow && ((OptionRow) sel).kind == OptionRow.VOLUME
                    && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                int step = code == KeyEvent.KEYCODE_DPAD_RIGHT ? VOLUME_STEP : -VOLUME_STEP;
                player.setVolume(player.volume() + step);
                optionsAdapter.notifyDataSetChanged();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                player.setMuted(!player.muted());
                showVolumeToast();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                player.togglePause();
                if (player.isPaused()) {
                    status.setText(R.string.paused);
                    status.setVisibility(View.VISIBLE);
                } else {
                    status.setVisibility(View.GONE);
                }
                return true;
            default:
                break;
        }
        if (guideVisible() || optionsVisible()) {
            if (keyCode == KeyEvent.KEYCODE_MENU) {
                if (optionsVisible()) {
                    hideOptions();
                } else {
                    showOptions();
                }
                return true;
            }
            return super.onKeyDown(keyCode, event); // the lists handle Up/Down/OK
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                zap(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                zap(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_LEFT:
                showGuide();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MENU:
                showOptions();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    // ---- Player panel rows ----------------------------------------------------------

    private static final class OptionRow {
        static final int HEADING = 0;
        static final int QUALITY = 1;
        static final int VOLUME = 2;
        static final int MUTE = 3;

        final int kind;
        final String text;
        final int height;

        OptionRow(int kind, String text, int height) {
            this.kind = kind;
            this.text = text;
            this.height = height;
        }
    }

    private void buildOptions() {
        options.clear();
        options.add(new OptionRow(OptionRow.HEADING, getString(R.string.quality), 0));
        options.add(new OptionRow(OptionRow.QUALITY, getString(R.string.quality_auto), LivePlayer.AUTO));
        List<Integer> heights = player.availableQualities();
        // Keep the chosen quality listed even on a channel that lacks it.
        if (player.quality() != LivePlayer.AUTO && !heights.contains(player.quality())) {
            heights.add(player.quality());
            heights.sort((a, b) -> b - a);
        }
        for (int h : heights) {
            options.add(new OptionRow(OptionRow.QUALITY, getString(R.string.quality_value, h), h));
        }
        options.add(new OptionRow(OptionRow.HEADING, getString(R.string.sound), 0));
        options.add(new OptionRow(OptionRow.VOLUME, getString(R.string.volume), 0));
        options.add(new OptionRow(OptionRow.MUTE, getString(R.string.mute), 0));
        optionsAdapter.notifyDataSetChanged();
    }

    private void onOptionClicked(OptionRow row) {
        if (row.kind == OptionRow.QUALITY) {
            player.setQuality(row.height);
        } else if (row.kind == OptionRow.MUTE) {
            player.setMuted(!player.muted());
        }
        optionsAdapter.notifyDataSetChanged();
    }

    private String optionSub(OptionRow row) {
        switch (row.kind) {
            case OptionRow.QUALITY:
                if (row.height == LivePlayer.AUTO) {
                    int h = player.playingHeight();
                    return h > 0 && player.quality() == LivePlayer.AUTO
                            ? getString(R.string.quality_auto_now, h) : getString(R.string.quality_auto_sub);
                }
                return player.availableQualities().contains(row.height) ? ""
                        : getString(R.string.quality_not_offered);
            default:
                return "";
        }
    }

    private String optionValue(OptionRow row) {
        switch (row.kind) {
            case OptionRow.QUALITY:
                return row.height == player.quality() ? getString(R.string.selected) : "";
            case OptionRow.VOLUME:
                // The arrows show that Left and Right change it.
                return player.muted() ? getString(R.string.muted)
                        : getString(R.string.volume_slider, player.volume());
            case OptionRow.MUTE:
                return getString(player.muted() ? R.string.on : R.string.off);
            default:
                return "";
        }
    }

    // ---- Adapters -------------------------------------------------------------------

    private final class GuideAdapter extends BaseAdapter {
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
        public View getView(int position, View convertView, ViewGroup parent) {
            int row = rows.get(position);
            View v = convertView != null ? convertView
                    : LayoutInflater.from(parent.getContext()).inflate(R.layout.item_channel, parent, false);
            TextView num = v.findViewById(R.id.num);
            TextView name = v.findViewById(R.id.name);
            TextView sub = v.findViewById(R.id.tamil);
            TextView tag = v.findViewById(R.id.watching);
            if (row == ROW_UPDATE) {
                num.setText("");
                name.setText(getString(R.string.update_row, update.version));
                sub.setText(R.string.update_row_sub);
                sub.setVisibility(View.VISIBLE);
                tag.setText(R.string.update_new);
                tag.setVisibility(View.VISIBLE);
            } else {
                Channel ch = channels.get(row);
                num.setText(String.valueOf(row + 1));
                name.setText(ch.name);
                sub.setText(ch.tamil);
                sub.setVisibility(ch.tamil.isEmpty() ? View.GONE : View.VISIBLE);
                tag.setText(R.string.watching);
                tag.setVisibility(row == current ? View.VISIBLE : View.GONE);
            }
            return v;
        }
    }

    private final class OptionsAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return options.size();
        }

        @Override
        public Object getItem(int position) {
            return options.get(position);
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
            return options.get(position).kind == OptionRow.HEADING ? 0 : 1;
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        @Override
        public boolean isEnabled(int position) {
            return options.get(position).kind != OptionRow.HEADING;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            OptionRow row = options.get(position);
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (row.kind == OptionRow.HEADING) {
                TextView v = convertView != null ? (TextView) convertView
                        : (TextView) inflater.inflate(R.layout.item_group, parent, false);
                v.setText(row.text);
                return v;
            }
            View v = convertView != null ? convertView
                    : inflater.inflate(R.layout.item_setting, parent, false);
            ((TextView) v.findViewById(R.id.title)).setText(row.text);
            TextView sub = v.findViewById(R.id.sub);
            String s = optionSub(row);
            sub.setText(s);
            sub.setVisibility(s.isEmpty() ? View.GONE : View.VISIBLE);
            TextView value = v.findViewById(R.id.value);
            String val = optionValue(row);
            value.setText(val);
            value.setEnabled(true);
            value.setVisibility(val.isEmpty() ? View.GONE : View.VISIBLE);
            return v;
        }
    }
}
