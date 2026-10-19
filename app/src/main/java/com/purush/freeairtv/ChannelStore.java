package com.purush.freeairtv;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The channel list and which channels are switched off.
 *
 * <p>Where the list comes from, first match wins:
 * <ol>
 *   <li>a file pushed over ADB to the app's own folder (for testing):
 *       /sdcard/Android/data/com.purush.freeairtv/files/channels.json</li>
 *   <li>the copy last downloaded from the GitHub repository's channels.json</li>
 *   <li>the list built into the APK</li>
 * </ol>
 * Hidden channels are remembered by id, so they stay hidden when the list is updated.
 */
final class ChannelStore {

    private static final String TAG = "FreeAirTv";
    private static final String OVERRIDE_FILE = "channels.json";
    private static final String REMOTE_CACHE = "channels-remote.json";
    private static final String PREFS = "channels";
    private static final String KEY_HIDDEN = "hidden_ids";
    private static final String KEY_ETAG = "remote_etag";
    private static final String KEY_FETCHED_AT = "remote_fetched_at";
    private static final String KEY_LAST = "last_channel_id";
    /** How often the remote list is re-checked while the app is in use. */
    static final long REFRESH_INTERVAL_MS = 6L * 60 * 60 * 1000;

    enum Source { ADB_OVERRIDE, GITHUB, BUILT_IN }

    private final Context context;
    private final List<Channel> all = new ArrayList<>();
    private Source source = Source.BUILT_IN;

    ChannelStore(Context context) {
        this.context = context.getApplicationContext();
    }

    // ---- Loading --------------------------------------------------------------------

    /** Reloads the list from the best available source. */
    void load() {
        File override = new File(context.getExternalFilesDir(null), OVERRIDE_FILE);
        if (tryLoad(override, Source.ADB_OVERRIDE)) {
            return;
        }
        if (tryLoad(new File(context.getFilesDir(), REMOTE_CACHE), Source.GITHUB)) {
            return;
        }
        try (InputStream in = context.getResources().openRawResource(R.raw.channels)) {
            all.clear();
            all.addAll(parse(readAll(in)));
            source = Source.BUILT_IN;
            Log.i(TAG, "Loaded " + all.size() + " built-in channels");
        } catch (Exception e) {
            Log.e(TAG, "Built-in channel list unreadable", e);
        }
    }

    private boolean tryLoad(File file, Source from) {
        if (!file.isFile()) {
            return false;
        }
        try (InputStream in = new FileInputStream(file)) {
            List<Channel> parsed = parse(readAll(in));
            all.clear();
            all.addAll(parsed);
            source = from;
            Log.i(TAG, "Loaded " + all.size() + " channels from " + from);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Ignoring bad channel list " + file, e);
            return false;
        }
    }

    /** Parses and validates a channels.json document; throws if anything is wrong. */
    static List<Channel> parse(String json) throws Exception {
        JSONArray array = new JSONArray(json);
        List<Channel> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.getJSONObject(i);
            String name = o.getString("name").trim();
            String url = o.getString("url").trim();
            String id = o.optString("id", name).trim();
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                throw new IllegalArgumentException("Channel " + (i + 1) + " has a non-http URL");
            }
            if (!ids.add(id)) {
                throw new IllegalArgumentException("Duplicate channel id " + id);
            }
            out.add(new Channel(id, name, o.optString("tamil", "").trim(),
                    o.optString("group", "").trim(), url));
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("No channels");
        }
        return out;
    }

    // ---- Remote list from GitHub ----------------------------------------------------

    boolean remoteDue() {
        long last = prefs().getLong(KEY_FETCHED_AT, 0);
        return System.currentTimeMillis() - last > REFRESH_INTERVAL_MS;
    }

    long remoteFetchedAt() {
        return prefs().getLong(KEY_FETCHED_AT, 0);
    }

    /**
     * Downloads channels.json from the repository and caches it if it is valid and new.
     * Blocking; call off the main thread. Returns true when the cached list changed.
     */
    boolean refreshFromGitHub() {
        String repo = context.getString(R.string.github_repo);
        String branch = context.getString(R.string.github_branch);
        HttpURLConnection c = null;
        try {
            URL url = new URL("https://raw.githubusercontent.com/" + repo + "/" + branch + "/channels.json");
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(15_000);
            c.setReadTimeout(15_000);
            c.setRequestProperty("User-Agent", "FreeAirTV/" + BuildInfo.versionName(context));
            String etag = prefs().getString(KEY_ETAG, null);
            File cache = new File(context.getFilesDir(), REMOTE_CACHE);
            if (etag != null && cache.isFile()) {
                c.setRequestProperty("If-None-Match", etag);
            }
            int code = c.getResponseCode();
            // Only a real answer counts as a check; failures are retried on the next tick.
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                prefs().edit().putLong(KEY_FETCHED_AT, System.currentTimeMillis()).apply();
                Log.i(TAG, "Channel list unchanged on GitHub");
                return false;
            }
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "Channel list fetch failed: HTTP " + code);
                return false;
            }
            prefs().edit().putLong(KEY_FETCHED_AT, System.currentTimeMillis()).apply();
            String body;
            try (InputStream in = c.getInputStream()) {
                body = readAll(in);
            }
            parse(body); // reject a broken list before it replaces a working one
            String old = cache.isFile() ? readFile(cache) : null;
            if (body.equals(old)) {
                return false;
            }
            File tmp = new File(context.getFilesDir(), REMOTE_CACHE + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(cache)) {
                throw new IOException("Could not save channel list");
            }
            prefs().edit().putString(KEY_ETAG, c.getHeaderField("ETag")).apply();
            Log.i(TAG, "Channel list updated from GitHub");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Channel list fetch failed", e);
            return false;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    // ---- Queries and settings -------------------------------------------------------

    Source source() {
        return source;
    }

    /** Every channel, including hidden ones (for Settings). */
    List<Channel> all() {
        return new ArrayList<>(all);
    }

    /** The channels to show in the guide and to zap through. */
    List<Channel> visible() {
        Set<String> hidden = hiddenIds();
        List<Channel> out = new ArrayList<>();
        for (Channel ch : all) {
            if (!hidden.contains(ch.id)) {
                out.add(ch);
            }
        }
        return out;
    }

    boolean isHidden(Channel ch) {
        return hiddenIds().contains(ch.id);
    }

    void setHidden(Channel ch, boolean hidden) {
        Set<String> ids = new HashSet<>(hiddenIds());
        if (hidden) {
            ids.add(ch.id);
        } else {
            ids.remove(ch.id);
        }
        prefs().edit().putStringSet(KEY_HIDDEN, ids).apply();
    }

    private Set<String> hiddenIds() {
        return prefs().getStringSet(KEY_HIDDEN, new HashSet<>());
    }

    String lastChannelId() {
        return prefs().getString(KEY_LAST, null);
    }

    void setLastChannelId(String id) {
        prefs().edit().putString(KEY_LAST, id).apply();
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---- IO -------------------------------------------------------------------------

    static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) {
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String readFile(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            return readAll(in);
        }
    }
}
