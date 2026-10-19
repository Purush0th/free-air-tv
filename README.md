# FreeAir TV

A simple live TV app for Fire TV and Android TV that plays free-to-air Tamil channels: devotional, general entertainment and news. Pick a channel from the guide with the remote and it plays full screen, in English and Tamil, with no ads, accounts or YouTube.

- **Channel guide** grouped as Devotional, General and News, with each channel's Tamil name.
- **Choose your channels**: Settings (Menu button) lists every channel with a Shown/Hidden switch. Hidden channels leave the guide and are skipped when changing channel.
- **Channel list updates from this repository**: edit [`channels.json`](channels.json) and every TV picks it up within six hours, or at once with Settings > Refresh channel list.
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

| In the guide | While watching |
| --- | --- |
| Up/Down: move · OK: watch · Back: exit · Menu: Settings | Up/Down: change channel · OK or Left: guide · Right: channel info · Play: jump to live |

## Editing the channel list

`channels.json` is a list of channels:

```json
{"id": "thanthi-tv", "name": "Thanthi TV", "tamil": "தந்தி டிவி", "group": "News", "url": "https://…/index.m3u8"}
```

- `id` stays the same for a channel even if its `url` changes, because TVs remember hidden channels and the last channel watched by it.
- `url` must be an HLS (`.m3u8`) stream over http(s).
- A broken file is ignored: TVs keep their last good list.

To try a list on one TV before publishing it: `.\set-channels.ps1 -File my-channels.json`, and `.\set-channels.ps1 -Reset` afterwards.

## Building

No Gradle; the build uses the Android SDK tools directly (`aapt2`, `javac`, `d8`, `apksigner`).

- **Windows:** `powershell -ExecutionPolicy Bypass -File build.ps1 -Version 1.1.0`
- **Linux:** `VERSION=1.1.0 KEYSTORE_FILE=… KEYSTORE_PASSWORD=… ./build.sh`

Needs JDK 17 and the Android SDK with `platforms;android-35` and `build-tools;35.0.0`. The artwork in `art/` is rendered into the app's images with `art/render.ps1`.

## Releasing

1. Push a tag: `git tag v1.1.0 && git push origin v1.1.0`.
2. GitHub Actions builds and signs the APK and publishes a release with the APK and its `.sha256`.
3. Installed apps see it within 12 hours, or at once with Settings > Check for updates.

The workflow needs two repository secrets: `KEYSTORE_BASE64` (the release keystore, base64-encoded) and `KEYSTORE_PASSWORD`. **The signing key is never committed.** Every update must be signed with the same key, so keep a backup of the keystore and its password somewhere safe.

## About the streams

FreeAir TV plays publicly reachable streams of free-to-air channels and hosts no video itself. Some streams are published by the broadcasters; others come from Indian streaming platforms that carry these channels. Streams can change or stop at any time; fix them in `channels.json`. If you are a broadcaster and want a stream removed, open an issue.
