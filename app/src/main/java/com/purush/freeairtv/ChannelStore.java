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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
 * A whole language can be switched off too; its channels then leave the guide whatever
 * their own setting.
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
    private static final String KEY_HIDDEN_LANGS = "hidden_langs";
    private static final String KEY_IDS_MIGRATED = "ids_migrated_v2";
    /** Language of channels that do not name one. */
    static final String DEFAULT_LANG = "ta-IN";
    /** Channel ids before the fa-tv-ch-N scheme, so hidden channels and the last channel carry over. */
    private static final Map<String, String> LEGACY_IDS = new HashMap<>();
    static {
        LEGACY_IDS.put("svbc2", "fa-tv-ch-1");
        LEGACY_IDS.put("sri-sankara", "fa-tv-ch-2");
        LEGACY_IDS.put("aastha-tamil", "fa-tv-ch-3");
        LEGACY_IDS.put("dd-tamil", "fa-tv-ch-4");
        LEGACY_IDS.put("raj-tv", "fa-tv-ch-5");
        LEGACY_IDS.put("raj-digital", "fa-tv-ch-6");
        LEGACY_IDS.put("polimer-tv", "fa-tv-ch-7");
        LEGACY_IDS.put("makkal-tv", "fa-tv-ch-8");
        LEGACY_IDS.put("vasanth-tv", "fa-tv-ch-9");
        LEGACY_IDS.put("news18-tn", "fa-tv-ch-10");
        LEGACY_IDS.put("puthiya-thalaimurai", "fa-tv-ch-11");
        LEGACY_IDS.put("thanthi-tv", "fa-tv-ch-12");
        LEGACY_IDS.put("sathiyam-tv", "fa-tv-ch-13");
        LEGACY_IDS.put("news7-tamil", "fa-tv-ch-14");
        LEGACY_IDS.put("polimer-news", "fa-tv-ch-15");
        LEGACY_IDS.put("news-tamil-24x7", "fa-tv-ch-16");
        LEGACY_IDS.put("malai-murasu", "fa-tv-ch-17");
    }
    /** How often the remote list is re-checked while the app is in use. */
    static final long REFRESH_INTERVAL_MS = 6L * 60 * 60 * 1000;

    enum Source { ADB_OVERRIDE, GITHUB, BUILT_IN }

    private final Context context;
    private final List<Channel> all = new ArrayList<>();
    private Source source = Source.BUILT_IN;

    ChannelStore(Context context) {
        this.context = context.getApplicationContext();
        migrateIds();
    }

    /** Once: rename stored channel ids from the old scheme to fa-tv-ch-N. */
    private void migrateIds() {
        SharedPreferences p = prefs();
        if (p.getBoolean(KEY_IDS_MIGRATED, false)) {
            return;
        }
        Set<String> hidden = new HashSet<>();
        for (String id : p.getStringSet(KEY_HIDDEN, new HashSet<>())) {
            String renamed = LEGACY_IDS.get(id);
            hidden.add(renamed != null ? renamed : id);
        }
        String last = p.getString(KEY_LAST, null);
        String lastRenamed = last == null ? null : LEGACY_IDS.get(last);
        p.edit()
                .putStringSet(KEY_HIDDEN, hidden)
                .putString(KEY_LAST, lastRenamed != null ? lastRenamed : last)
                .putBoolean(KEY_IDS_MIGRATED, true)
                .apply();
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
            // "tamil" is the field's name before lists had several languages.
            String locale = o.optString("locale", o.optString("tamil", "")).trim();
            String lang = o.optString("lang", "").trim();
            out.add(new Channel(id, name, locale, o.optString("group", "").trim(), url,
                    lang.isEmpty() ? DEFAULT_LANG : lang));
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
        Set<String> hiddenLangs = hiddenLangs();
        List<Channel> out = new ArrayList<>();
        for (Channel ch : all) {
            if (!hidden.contains(ch.id) && !hiddenLangs.contains(ch.lang)) {
                out.add(ch);
            }
        }
        return out;
    }

    /** Language tags in the order they first appear in the list. */
    List<String> languages() {
        Set<String> langs = new LinkedHashSet<>();
        for (Channel ch : all) {
            langs.add(ch.lang);
        }
        return new ArrayList<>(langs);
    }

    /** Every channel in one language, in list order. */
    List<Channel> channelsIn(String lang) {
        List<Channel> out = new ArrayList<>();
        for (Channel ch : all) {
            if (ch.lang.equals(lang)) {
                out.add(ch);
            }
        }
        return out;
    }

    boolean isLanguageHidden(String lang) {
        return hiddenLangs().contains(lang);
    }

    void setLanguageHidden(String lang, boolean hidden) {
        Set<String> langs = new HashSet<>(hiddenLangs());
        if (hidden) {
            langs.add(lang);
        } else {
            langs.remove(lang);
        }
        prefs().edit().putStringSet(KEY_HIDDEN_LANGS, langs).apply();
    }

    private Set<String> hiddenLangs() {
        return prefs().getStringSet(KEY_HIDDEN_LANGS, new HashSet<>());
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

    /** Shows or hides several channels in one write (Enable All / Disable All). */
    void setHidden(List<Channel> channels, boolean hidden) {
        Set<String> ids = new HashSet<>(hiddenIds());
        for (Channel ch : channels) {
            if (hidden) {
                ids.add(ch.id);
            } else {
                ids.remove(ch.id);
            }
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
