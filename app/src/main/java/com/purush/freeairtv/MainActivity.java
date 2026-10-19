package com.purush.freeairtv;

import android.app.Activity;
import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.VideoView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * FreeAir TV: free-to-air channels played full screen, with a channel guide.
 * The channel list comes from {@link ChannelStore}; updates from {@link Updater}.
 */
public class MainActivity extends Activity {

    private static final String TAG = "FreeAirTv";
    private static final long RETRY_DELAY_MS = 5_000;
    // Restart if buffering never recovers (live HLS sometimes stalls without an error).
    private static final long STALL_TIMEOUT_MS = 30_000;
    private static final long BANNER_MS = 4_000;
    // After this many failed attempts in a row, suggest moving on.
    private static final int HINT_AFTER_FAILURES = 3;
    // How often to see whether the channel list or a release is due for a check.
    private static final long BACKGROUND_TICK_MS = 60L * 60 * 1000;

    /** Guide rows that are not channels. Group headings are -1 - groupIndex. */
    private static final int ROW_UPDATE = Integer.MIN_VALUE;
    private static final int ROW_SETTINGS = Integer.MIN_VALUE + 1;

    /** Tamil names for the standard groups; other groups show their name alone. */
    private static final Map<String, String> GROUP_TAMIL = new HashMap<>();
    static {
        GROUP_TAMIL.put("Devotional", "பக்தி");
        GROUP_TAMIL.put("General", "பொழுதுபோக்கு");
        GROUP_TAMIL.put("News", "செய்திகள்");
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private final Runnable retry = () -> play(this.current);
    private final Runnable stallWatchdog = () -> {
        this.failures++;
        play(this.current);
    };
    private final Runnable hideBanner = () -> this.banner.setVisibility(View.GONE);
    private final Runnable backgroundTick = this::checkInBackground;

    private ChannelStore store;
    private Updater updater;
    private final List<Channel> channels = new ArrayList<>();
    private final List<Integer> rows = new ArrayList<>();
    private final List<String> groups = new ArrayList<>();
    private Updater.Release update;
    private int current;
    private int failures;

    private VideoView video;
    private TextView status;
    private View banner;
    private TextView bannerNum;
    private TextView bannerName;
    private TextView bannerTamil;
    private View guide;
    private ListView list;
    private GuideAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        store = new ChannelStore(this);
        updater = new Updater(this);
        video = findViewById(R.id.video);
        status = findViewById(R.id.status);
        banner = findViewById(R.id.banner);
        bannerNum = findViewById(R.id.banner_num);
        bannerName = findViewById(R.id.banner_name);
        bannerTamil = findViewById(R.id.banner_tamil);
        guide = findViewById(R.id.guide);
        list = findViewById(R.id.list);

        adapter = new GuideAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            int row = rows.get(position);
            if (row == ROW_SETTINGS) {
                startActivity(new Intent(this, SettingsActivity.class));
            } else if (row == ROW_UPDATE) {
                startActivity(new Intent(this, SettingsActivity.class)
                        .putExtra(SettingsActivity.EXTRA_INSTALL_UPDATE, true));
            } else if (row >= 0) {
                if (row != current || !video.isPlaying()) {
                    failures = 0;
                    play(row);
                }
                hideGuide();
            }
        });

        video.setOnPreparedListener(mp -> {
            handler.removeCallbacks(stallWatchdog);
            failures = 0;
            status.setVisibility(View.GONE);
        });
        video.setOnInfoListener((mp, what, extra) -> {
            if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                showConnecting();
                armWatchdog();
            } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END
                    || what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                handler.removeCallbacks(stallWatchdog);
                status.setVisibility(View.GONE);
            }
            return false;
        });
        video.setOnErrorListener((mp, what, extra) -> {
            Log.w(TAG, "Playback error " + what + "/" + extra + " on " + currentName());
            scheduleRetry();
            return true; // suppress the platform "Can't play this video" dialog
        });
        // A live stream "completing" means the feed ended or dropped.
        video.setOnCompletionListener(mp -> scheduleRetry());
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
            showGuide();
        } else {
            failures = 0;
            play(current);
            showGuide();
        }
        handler.post(backgroundTick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacksAndMessages(null);
        video.stopPlayback();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        background.shutdownNow();
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
        adapter.notifyDataSetChanged();
    }

    /** Group headings wherever the group changes; Update first and Settings last. */
    private void buildRows() {
        rows.clear();
        groups.clear();
        if (update != null) {
            rows.add(ROW_UPDATE);
        }
        String last = null;
        for (int i = 0; i < channels.size(); i++) {
            String g = channels.get(i).group;
            if (!g.isEmpty() && !g.equals(last)) {
                groups.add(g);
                rows.add(-groups.size());
            }
            last = g;
            rows.add(i);
        }
        rows.add(ROW_SETTINGS);
    }

    private static boolean isHeading(int row) {
        return row < 0 && row != ROW_UPDATE && row != ROW_SETTINGS;
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
            adapter.notifyDataSetChanged();
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
        if (failures == 0) {
            showConnecting();
        }
        video.stopPlayback();
        video.setVideoURI(Uri.parse(ch.url));
        video.start(); // VideoView begins playback as soon as it is prepared
        armWatchdog();
        list.setItemChecked(rowOf(current), true);
        adapter.notifyDataSetChanged();
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

    private void scheduleRetry() {
        handler.removeCallbacks(stallWatchdog);
        failures++;
        int msg = failures >= HINT_AFTER_FAILURES ? R.string.retrying_hint : R.string.retrying;
        status.setText(getString(msg, currentName()));
        status.setVisibility(View.VISIBLE);
        handler.removeCallbacks(retry);
        handler.postDelayed(retry, RETRY_DELAY_MS);
    }

    private void showConnecting() {
        status.setText(getString(R.string.connecting, currentName()));
        status.setVisibility(View.VISIBLE);
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
        banner.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideBanner);
        handler.postDelayed(hideBanner, BANNER_MS);
    }

    // ---- Guide and remote -----------------------------------------------------------

    private boolean guideVisible() {
        return guide.getVisibility() == View.VISIBLE;
    }

    private void showGuide() {
        banner.setVisibility(View.GONE);
        guide.setVisibility(View.VISIBLE);
        list.requestFocus();
        if (channels.isEmpty()) {
            list.setSelection(rows.size() - 1);
            return;
        }
        // Open with the channel's group heading in view; deep in a group, keep two rows above.
        int row = rowOf(current);
        int top = row;
        while (top > 0 && rows.get(top - 1) >= 0 && row - top < 2) {
            top--;
        }
        if (top > 0 && isHeading(rows.get(top - 1))) {
            top--;
        }
        list.setSelection(row);
        list.setSelectionFromTop(row, rowTop(top, row));
    }

    /** Distance from the list top to {@code row} when {@code first} is the top row. */
    private int rowTop(int first, int row) {
        float dp = 0;
        for (int i = first; i < row; i++) {
            dp += isHeading(rows.get(i)) ? 46 : 66; // heading and row heights, in dp
        }
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                getResources().getDisplayMetrics());
    }

    private void hideGuide() {
        guide.setVisibility(View.GONE);
        showBanner();
    }

    @Override
    public void onBackPressed() {
        if (guideVisible() || channels.isEmpty()) {
            super.onBackPressed(); // Back on the list leaves the app
        } else {
            showGuide();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        if (guideVisible()) {
            return super.onKeyDown(keyCode, event); // ListView handles Up/Down/OK
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                zap(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                zap(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_LEFT:
                showGuide();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                showBanner();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                failures = 0;
                play(current); // reload and jump back to the live edge
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
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return isHeading(rows.get(position)) ? 0 : 1;
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        @Override
        public boolean isEnabled(int position) {
            return !isHeading(rows.get(position)); // headings are skipped by the D-pad
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            int row = rows.get(position);
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (isHeading(row)) {
                TextView v = convertView != null ? (TextView) convertView
                        : (TextView) inflater.inflate(R.layout.item_group, parent, false);
                String g = groups.get(-row - 1);
                String ta = GROUP_TAMIL.get(g);
                v.setText(ta == null ? g : g + "  ·  " + ta);
                return v;
            }
            View v = convertView != null ? convertView
                    : inflater.inflate(R.layout.item_channel, parent, false);
            TextView num = v.findViewById(R.id.num);
            TextView name = v.findViewById(R.id.name);
            TextView sub = v.findViewById(R.id.tamil);
            TextView tag = v.findViewById(R.id.watching);
            if (row == ROW_SETTINGS) {
                num.setText("");
                name.setText(R.string.settings);
                sub.setText(R.string.settings_sub);
                sub.setVisibility(View.VISIBLE);
                tag.setVisibility(View.GONE);
            } else if (row == ROW_UPDATE) {
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
}
