package com.purush.freeairtv;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Self-update from the repository's GitHub Releases.
 *
 * <p>Android releases are tagged {@code android-vX.Y.Z} (older ones {@code vX.Y.Z}); Roku
 * releases ({@code roku-v*}) share the repository and are skipped. A release carries two
 * assets: {@code FreeAirTV-android-<version>.apk} and its {@code .apk.sha256} (the hex digest,
 * optionally followed by the file name). The APK is downloaded, its checksum verified, and handed to the system
 * installer, which asks for one confirmation on the TV. Android itself refuses the
 * update unless it is signed with the same key as the installed app.
 */
final class Updater {

    private static final String TAG = "FreeAirTv";
    private static final String PREFS = "updates";
    private static final String KEY_CHECKED_AT = "checked_at";
    private static final String KEY_VERSION = "available_version";
    private static final String KEY_APK_URL = "apk_url";
    private static final String KEY_SHA_URL = "sha_url";
    /** How often GitHub is asked for a new release while the app is in use. */
    static final long CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000;

    /** A release newer than the installed app. */
    static final class Release {
        final String version;
        final String apkUrl;
        final String shaUrl;

        Release(String version, String apkUrl, String shaUrl) {
            this.version = version;
            this.apkUrl = apkUrl;
            this.shaUrl = shaUrl;
        }
    }

    private final Context context;

    Updater(Context context) {
        this.context = context.getApplicationContext();
    }

    boolean checkDue() {
        return System.currentTimeMillis() - prefs().getLong(KEY_CHECKED_AT, 0) > CHECK_INTERVAL_MS;
    }

    long checkedAt() {
        return prefs().getLong(KEY_CHECKED_AT, 0);
    }

    /** The newer release found by the last check, or null. */
    Release available() {
        SharedPreferences p = prefs();
        String v = p.getString(KEY_VERSION, null);
        if (v == null || compareVersions(v, BuildInfo.versionName(context)) <= 0) {
            return null;
        }
        return new Release(v, p.getString(KEY_APK_URL, null), p.getString(KEY_SHA_URL, null));
    }

    /**
     * Asks GitHub for the newest Android release. Blocking; call off the main thread.
     * Returns the release if it is newer than this app, otherwise null.
     *
     * <p>Lists recent releases rather than reading "latest": Roku releases live in the same
     * repository, and GitHub does not list releases in version order.
     */
    Release check() {
        String repo = context.getString(R.string.github_repo);
        HttpURLConnection c = null;
        try {
            URL url = new URL("https://api.github.com/repos/" + repo + "/releases?per_page=50");
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(15_000);
            c.setReadTimeout(15_000);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("User-Agent", "FreeAirTV/" + BuildInfo.versionName(context));
            int code = c.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                // Not counted as a check, so it is retried on the next tick.
                Log.w(TAG, "Release check failed: HTTP " + code);
                return null;
            }
            prefs().edit().putLong(KEY_CHECKED_AT, System.currentTimeMillis()).apply();
            JSONArray releases;
            try (InputStream in = c.getInputStream()) {
                releases = new JSONArray(ChannelStore.readAll(in));
            }
            String bestVersion = null;
            String bestApk = null;
            String bestSha = null;
            for (int r = 0; r < releases.length(); r++) {
                JSONObject release = releases.getJSONObject(r);
                if (release.optBoolean("draft") || release.optBoolean("prerelease")) {
                    continue;
                }
                String version = androidVersion(release.optString("tag_name"));
                if (version == null || (bestVersion != null && compareVersions(version, bestVersion) <= 0)) {
                    continue;
                }
                String apkUrl = null;
                String shaUrl = null;
                JSONArray assets = release.optJSONArray("assets");
                for (int i = 0; assets != null && i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    String name = a.getString("name");
                    if (name.endsWith(".apk")) {
                        apkUrl = a.getString("browser_download_url");
                    } else if (name.endsWith(".apk.sha256")) {
                        shaUrl = a.getString("browser_download_url");
                    }
                }
                if (apkUrl != null && shaUrl != null) {
                    bestVersion = version;
                    bestApk = apkUrl;
                    bestSha = shaUrl;
                }
            }
            if (bestVersion == null) {
                Log.w(TAG, "No Android release with an APK and checksum");
                return null;
            }
            prefs().edit().putString(KEY_VERSION, bestVersion).putString(KEY_APK_URL, bestApk)
                    .putString(KEY_SHA_URL, bestSha).apply();
            Log.i(TAG, "Newest release " + bestVersion + ", installed " + BuildInfo.versionName(context));
            return available();
        } catch (Exception e) {
            Log.w(TAG, "Release check failed", e);
            return null;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /** "android-v1.2.3" or "v1.2.3" gives "1.2.3"; any other tag (such as roku-v*) gives null. */
    static String androidVersion(String tag) {
        String t = tag.startsWith("android-") ? tag.substring("android-".length()) : tag;
        if (!t.matches("[vV]\\d+(\\.\\d+)*")) {
            return null;
        }
        return t.substring(1);
    }

    /**
     * Downloads the release, verifies its checksum and starts the system installer.
     * Blocking; call off the main thread. Throws with a readable message on failure.
     */
    void downloadAndInstall(Release release) throws Exception {
        String expected = fetchText(release.shaUrl).trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
        if (!expected.matches("[0-9a-f]{64}")) {
            throw new IOException("The release checksum is unreadable.");
        }
        File apk = new File(context.getCacheDir(), "update.apk");
        download(release.apkUrl, apk);
        String actual = sha256(apk);
        if (!actual.equals(expected)) {
            apk.delete();
            throw new IOException("The download is damaged (checksum mismatch).");
        }
        install(apk);
    }

    private void install(File apk) throws IOException {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        int sessionId = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(sessionId)) {
            try (OutputStream out = session.openWrite("base.apk", 0, apk.length());
                 InputStream in = new FileInputStream(apk)) {
                byte[] buf = new byte[65536];
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                }
                session.fsync(out);
            }
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) {
                flags |= PendingIntent.FLAG_MUTABLE; // the installer adds its result extras
            }
            Intent result = new Intent(context, InstallResultReceiver.class);
            PendingIntent pending = PendingIntent.getBroadcast(context, sessionId, result, flags);
            session.commit(pending.getIntentSender());
        }
        Log.i(TAG, "Update handed to the system installer");
    }

    // ---- Helpers --------------------------------------------------------------------

    private String fetchText(String url) throws IOException {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            return ChannelStore.readAll(in);
        } finally {
            c.disconnect();
        }
    }

    private void download(String url, File to) throws IOException {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
            }
        } finally {
            c.disconnect();
        }
    }

    /** Opens a GET, following GitHub's redirect to its download host. */
    private HttpURLConnection open(String url) throws IOException {
        for (int hops = 0; hops < 5; hops++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(20_000);
            c.setReadTimeout(60_000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "FreeAirTV/" + BuildInfo.versionName(context));
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                url = new URL(new URL(url), c.getHeaderField("Location")).toString();
                c.disconnect();
                if (!url.startsWith("https://")) {
                    throw new IOException("Refusing a non-https redirect.");
                }
                continue;
            }
            if (code != HttpURLConnection.HTTP_OK) {
                c.disconnect();
                throw new IOException("Download failed (HTTP " + code + ").");
            }
            return c;
        }
        throw new IOException("Too many redirects.");
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) {
                md.update(buf, 0, n);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) {
            sb.append(String.format(Locale.ROOT, "%02x", b));
        }
        return sb.toString();
    }

    /** Compares dotted versions numerically: 1.10 > 1.9. Non-numeric parts count as 0. */
    static int compareVersions(String a, String b) {
        String[] x = a.split("[.-]");
        String[] y = b.split("[.-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? toInt(x[i]) : 0;
            int q = i < y.length ? toInt(y[i]) : 0;
            if (p != q) {
                return Integer.compare(p, q);
            }
        }
        return 0;
    }

    private static int toInt(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
