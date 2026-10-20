# FreeAir TV

A simple live TV app for Fire TV and Android TV that plays free-to-air Tamil channels: devotional, general entertainment and news. Pick a channel from the guide with the remote and it plays full screen, in English and Tamil, with no ads, accounts or YouTube.

- **Channel list** in a side panel, with each channel's name in its own language and script.
- **Picture & Sound**: pick the stream quality (Auto, or any resolution the channel offers, such as 1080p or 720p; the choice applies to every channel), the app volume, and mute. Playback uses [Media3 ExoPlayer](https://developer.android.com/media/media3/exoplayer).
- **Choose your channels**: Settings (the gear at the top of the channel list, or Menu on the remote) lists the languages on the left, each with a tick that switches the whole language on or off, and that language's channels on the right, each with its own tick. Unticked channels leave the list and are skipped when changing channel.
- **Channel list updates from this repository**: edit [`channels.json`](channels.json) and every TV picks it up within six hours, or at once with Settings > Refresh Channel List.
- **Updates itself from GitHub Releases**: the app finds new versions, downloads them, checks their SHA-256, and asks for one press of Install on the TV.

## Install on a Fire TV

1. On the TV: Settings > My Fire TV > Developer options. Turn on **ADB debugging** and **Apps from unknown sources** (or **Install unknown apps** for the app you use to install).
2. Download `FreeAirTV-<version>.apk` from the [latest release](../../releases/latest).
3. Install it, either with the Downloader app or from a computer on the same network:
   ```bash
   adb connect <tv-ip>:5555
   adb install FreeAirTV-<version>.apk
   ```
4. Let FreeAir TV install its own updates (once):
   ```bash
   adb shell appops set com.purush.freeairtv REQUEST_INSTALL_PACKAGES allow
   ```

## Remote

| Button | While watching | In a panel |
| --- | --- | --- |
| Up / Down | Next / previous channel | Move |
| OK or Left | Open the channel list | Choose |
| Right | Open Picture & Sound | Left/Right change the volume on its row |
| Menu (≡) | Open Settings | Open Settings |
| Back | Twice to leave the app | Close the panel |
| Play/Pause | Pause; press again to return to live | |

The app volume is separate from the TV's: the Fire TV remote's volume buttons still control the TV.

## Editing the channel list

`channels.json` is a list of channels:

```json
{"id": "fa-tv-ch-12", "name": "Thanthi TV", "locale": "தந்தி டிவி", "group": "News", "url": "https://…/index.m3u8", "lang": "ta-IN"}
```

- `id` is the channel's primary key: TVs store each channel's tick and the last channel watched by it. Never change or reuse an id; a new channel gets a new one.
- `isActive` (optional, default `true`): set it to `false` to retire a channel without deleting it. TVs hide it but keep its tick, so it comes back as it was if it is reactivated. A channel deleted from the file loses its tick.
- `url` must be an HLS (`.m3u8`) stream over http(s).
- `locale` is the channel's name in its own language and script, shown under `name`; it may be empty.
- `lang` is a language tag such as `ta-IN`, `hi-IN` or `en-IN`. Settings groups channels by it, and a whole language can be switched off. Without it a channel counts as Tamil.
- `group` (Devotional, General, News…) sorts a language's channels under headings in Settings.
- A broken file is ignored: TVs keep their last good list.

To try a list on one TV before publishing it: `.\set-channels.ps1 -File my-channels.json`, and `.\set-channels.ps1 -Reset` afterwards.

## Building

No Gradle; the build uses the Android SDK tools directly (`aapt2`, `javac`, `d8`, `apksigner`). Libraries (Media3 ExoPlayer and its dependencies) are pinned with SHA-256 checksums in [`deps.lock`](deps.lock); the build downloads them into `.deps/` and stops if a checksum doesn't match.

- **Windows:** `powershell -ExecutionPolicy Bypass -File build.ps1 -Version 1.1.0`
- **Linux:** `VERSION=1.1.0 KEYSTORE_FILE=… KEYSTORE_PASSWORD=… ./build.sh`

Needs JDK 17 and the Android SDK with `platforms;android-35` and `build-tools;35.0.0`. The artwork in `art/` is rendered into the app's images with `art/render.ps1`.

## Releasing

1. Push a tag: `git tag v1.1.0 && git push origin v1.1.0`.
2. GitHub Actions builds and signs the APK and publishes a release with the APK and its `.sha256`.
3. Installed apps see it within 12 hours, or at once with Settings > Check for Updates.

The workflow needs two repository secrets: `KEYSTORE_BASE64` (the release keystore, base64-encoded) and `KEYSTORE_PASSWORD`. **The signing key is never committed.** Every update must be signed with the same key, so keep a backup of the keystore and its password somewhere safe.

## About the streams

FreeAir TV plays publicly reachable streams of free-to-air channels and hosts no video itself. Some streams are published by the broadcasters; others come from Indian streaming platforms that carry these channels. Streams can change or stop at any time; fix them in `channels.json`. If you are a broadcaster and want a stream removed, open an issue.
