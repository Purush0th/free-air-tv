package com.purush.freeairtv;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.SurfaceView;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Media3 ExoPlayer for live HLS, with the three things the TV needs: a chosen quality that
 * applies to every channel, an app volume, and mute. Settings persist between launches.
 */
@OptIn(markerClass = UnstableApi.class)
final class LivePlayer {

    /** What the screen is told about. All calls arrive on the main thread. */
    interface Listener {
        void onBuffering();

        void onPlaying();

        /** The stream failed; the screen decides when to retry. */
        void onError(PlaybackException error);

        void onVideoSize(int width, int height);

        /** The qualities on offer or the one playing changed. */
        void onQualityChanged();
    }

    /** "Auto" is height 0. */
    static final int AUTO = 0;
    private static final String PREFS = "player";
    private static final String KEY_QUALITY = "quality_height";
    private static final String KEY_VOLUME = "volume_percent";

    private final Context context;
    private final ExoPlayer player;
    private final Listener listener;
    private int quality;
    private int volume;
    private boolean muted;

    LivePlayer(Context context, SurfaceView surface, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        SharedPreferences p = prefs();
        quality = p.getInt(KEY_QUALITY, AUTO);
        volume = p.getInt(KEY_VOLUME, 100);

        player = new ExoPlayer.Builder(context).build();
        player.setVideoSurfaceView(surface);
        player.setVolume(volume / 100f);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_BUFFERING) {
                    LivePlayer.this.listener.onBuffering();
                } else if (state == Player.STATE_READY) {
                    LivePlayer.this.listener.onPlaying();
                } else if (state == Player.STATE_ENDED) {
                    // A live stream "ending" means the feed dropped.
                    LivePlayer.this.listener.onError(null);
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    // Fell behind the live edge: jump back to live without a full restart.
                    player.seekToDefaultPosition();
                    player.prepare();
                    return;
                }
                LivePlayer.this.listener.onError(error);
            }

            @Override
            public void onVideoSizeChanged(VideoSize size) {
                if (size.width > 0 && size.height > 0) {
                    LivePlayer.this.listener.onVideoSize(
                            Math.round(size.width * size.pixelWidthHeightRatio), size.height);
                }
                LivePlayer.this.listener.onQualityChanged();
            }

            @Override
            public void onTracksChanged(Tracks tracks) {
                applyQuality();
                LivePlayer.this.listener.onQualityChanged();
            }
        });
    }

    void play(String url) {
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent("FreeAirTV/" + BuildInfo.versionName(context))
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(15_000);
        HlsMediaSource source = new HlsMediaSource.Factory(http)
                .createMediaSource(MediaItem.fromUri(url));
        // Drop any per-channel override; applyQuality() sets it again once tracks are known.
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                .setMaxVideoSize(Integer.MAX_VALUE, quality == AUTO ? Integer.MAX_VALUE : quality)
                .build());
        player.setMediaSource(source);
        player.setPlayWhenReady(true);
        player.prepare();
    }

    /** Back to the live edge after a pause. */
    void resumeLive() {
        player.seekToDefaultPosition();
        player.setPlayWhenReady(true);
        if (player.getPlaybackState() == Player.STATE_IDLE) {
            player.prepare();
        }
    }

    void togglePause() {
        if (player.isPlaying()) {
            player.pause();
        } else {
            resumeLive();
        }
    }

    boolean isPaused() {
        return !player.getPlayWhenReady();
    }

    void stop() {
        player.stop();
    }

    void release() {
        player.release();
    }

    // ---- Quality --------------------------------------------------------------------

    /** Heights the current channel offers, highest first, e.g. [1080, 720, 360]. */
    List<Integer> availableQualities() {
        TreeSet<Integer> heights = new TreeSet<>((a, b) -> b - a);
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != C.TRACK_TYPE_VIDEO) {
                continue;
            }
            for (int i = 0; i < g.length; i++) {
                Format f = g.getTrackFormat(i);
                if (g.isTrackSupported(i) && f.height > 0) {
                    heights.add(f.height);
                }
            }
        }
        return new ArrayList<>(heights);
    }

    /** The chosen quality (AUTO or a height); applies to every channel. */
    int quality() {
        return quality;
    }

    /** Height of the picture playing now, or 0 if unknown. */
    int playingHeight() {
        Format f = player.getVideoFormat();
        return f != null ? f.height : 0;
    }

    void setQuality(int height) {
        quality = height;
        prefs().edit().putInt(KEY_QUALITY, height).apply();
        applyQuality();
        listener.onQualityChanged();
    }

    /**
     * Pins the exact height when this channel has it; otherwise caps at the chosen height,
     * so "720p" on a channel with only 1080p and 576p plays 576p rather than failing.
     */
    private void applyQuality() {
        TrackSelectionParameters.Builder b = player.getTrackSelectionParameters().buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO);
        if (quality == AUTO) {
            b.setMaxVideoSize(Integer.MAX_VALUE, Integer.MAX_VALUE);
        } else {
            b.setMaxVideoSize(Integer.MAX_VALUE, quality);
            for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
                if (g.getType() != C.TRACK_TYPE_VIDEO) {
                    continue;
                }
                List<Integer> exact = new ArrayList<>();
                for (int i = 0; i < g.length; i++) {
                    if (g.isTrackSupported(i) && g.getTrackFormat(i).height == quality) {
                        exact.add(i);
                    }
                }
                if (!exact.isEmpty()) {
                    TrackGroup group = g.getMediaTrackGroup();
                    b.setOverrideForType(new TrackSelectionOverride(group, exact));
                }
            }
        }
        player.setTrackSelectionParameters(b.build());
    }

    // ---- Sound ----------------------------------------------------------------------

    int volume() {
        return volume;
    }

    boolean muted() {
        return muted;
    }

    /** App volume in percent, in steps of 10; unmutes. */
    void setVolume(int percent) {
        volume = Math.max(0, Math.min(100, percent));
        muted = false;
        prefs().edit().putInt(KEY_VOLUME, volume).apply();
        player.setVolume(volume / 100f);
    }

    void setMuted(boolean mute) {
        muted = mute;
        player.setVolume(mute ? 0f : volume / 100f);
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
