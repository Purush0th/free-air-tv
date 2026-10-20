# FreeAir TV for Roku

A Roku version of FreeAir TV, written as a SceneGraph/BrightScript channel. Roku can't run
Android APKs, so this is separate code. It uses the same `channels.json` and artwork as the
Fire TV app, and the Android app in `app/` is untouched.

Version 0.1.7 does the following:

- **Channels.** On launch it plays the last channel full screen straight away, using the list it
  saved last time, or the copy of `channels.json` inside the package. Then it fetches
  `https://raw.githubusercontent.com/Purush0th/free-air-tv/main/channels.json` in the
  background. A channel with `"isActive": false` is skipped. Editing `channels.json` on `main`
  therefore updates the Roku too: no new build is needed.
- **Remote.**
  | Key | Picture showing | Channel list / Picture & Sound open | Settings |
  |---|---|---|---|
  | Up / Down | next / previous channel | move through the list | move in a pane |
  | OK | open the channel list | watch the channel / choose the option | tick, or act |
  | Left | open the channel list | close Picture & Sound | back to the left pane |
  | Right | open Picture & Sound | close the channel list | into the right pane |
  | Back | exit FreeAir TV | close the panel | left pane, then leave |

  `*` doesn't reach the channel: while video plays, Roku OS keeps it for its own options
  overlay (Volume mode, captions).
- **Picture & Sound** (on the right, like the Fire TV app's player panel). **Quality** lists
  Auto plus each picture size the channel's stream offers. A chosen size is remembered and
  applies to every channel, using the best picture no taller than it. **Mute** turns the
  channel's sound off and on. Roku apps can't set the volume, so use the remote's volume buttons.
  At the bottom, the **Settings** button opens Settings.
- **Settings** (like the Fire TV Settings screen, two panes). Under **Channels** there is a row
  per language, which OK turns on or off as a whole. On the right are **All Channels** and a
  tick for each channel. Channels that are off are left out of the channel list and of Up/Down.
  Under **App**: **Updates** (check for a new Roku version, or for channel list changes) and
  **About**.
- **Now-watching card.** After a channel change, a translucent card in the top-left shows the
  number, the name, the Tamil name, LIVE with the picture size (for example "LIVE · 720p"), and
  the time. It stays up while the channel connects
  and goes away 4 s after the picture starts. If a stream drops, the channel retries it 3 times
  (5 s apart), then shows "Not available now".
- **Remembered between launches** (stored in `roRegistrySection "FreeAirTV"`): the last
  channel's id, the channels and languages turned off, the chosen quality, the last channel list
  that downloaded, and the last update check.

## Sideload it onto your Roku

1. **Turn on developer mode** (you only do this once per Roku). On the Roku remote, press
   **Home 3×, Up 2×, Right, Left, Right, Left, Right**. Make a note of the IP address the
   screen shows, choose **Enable installer and restart**, accept the agreement, and set a
   web-server password. The user name is always `rokudev`.
2. **Build the zip** on the PC:
   ```powershell
   powershell -ExecutionPolicy Bypass -File roku\package.ps1
   ```
   This first redraws the Tamil names (see below), then writes
   `build\FreeAirTV-roku-<version>.zip`. It needs Node and Chrome. On Linux or macOS,
   `./roku/package.sh` does the same and also needs `zip`.
3. **Install it.** In a browser on the same network, open `http://<roku-ip>` and sign in as
   `rokudev` with your password. Choose **Upload**, pick the zip, and click **Install with zip**
   (on later installs the button says **Replace with zip**). The channel starts on its own, and
   it appears on the home screen as **FreeAir TV** with a "dev" badge.

Only one sideloaded channel can be installed at a time, and installing another one replaces
it. The sideloaded channel stays after the Roku restarts.

## Updates

Once one version with this feature is installed, later versions install from the app:

1. **Publish** a release by pushing a Roku version tag:
   ```bash
   git tag roku-v0.1.8 && git push origin roku-v0.1.8
   ```
   `.github/workflows/roku-release.yml` packages the zip (with that version written into the
   manifest), adds its `.sha256`, and attaches both to a GitHub release. The release isn't
   marked "latest", because the Fire TV app updates from the latest release and must keep
   finding its APK there.
2. **The channel notices.** It looks for the newest `roku-v*` release at launch (at most every
   12 hours) and when you select **Settings → Updates → Check for App Update**. A newer one
   shows as **Install Version 0.1.8** with a **New** tag.
3. **Install it** by selecting that row. A sideloaded channel can't replace itself directly;
   only the Roku's developer installer can. So the channel downloads the zip, checks its
   SHA-256, and posts it to the installer on the Roku itself (`http://<roku-ip>/plugin_install`).
   The first time, it asks for the developer password (user `rokudev`) on the Roku keyboard and
   then keeps it in the channel's registry on that Roku. The new version then starts on its own.
   If the password is refused, it's forgotten and asked again.

From a PC, `powershell -ExecutionPolicy Bypass -File roku\update.ps1` does the same: it installs
the newest release, or `-Zip <file>` installs a zip you built. Use it for the first install of a
version with in-app updates, or if the in-app install fails.

If you want updates with no developer mode at all, the route is a Roku **beta channel**: Roku
developer dashboard → Beta channel, invite up to 20 testers, and Roku installs and updates it like
a store channel. It needs a free Roku developer account and a signed package, and a beta channel
expires after 120 days, so you republish it.

**Debug console.** `telnet <roku-ip> 8085` shows the channel's output: each key press
(`FreeAir key: …`), the `FreeAir: error …` lines printed when a stream fails, and any
BrightScript crash. `telnet <roku-ip> 8080` is the SceneGraph debug server. There, `sgnodes all`
lists the nodes and which one has focus, and `press u` (`d`, `l`, `r`, `s` for OK, `k` for
Back, `p` for Play/Pause) presses a key. Neither port needs the password.

## Files

| Path | What it is |
|---|---|
| `manifest` | Channel title, version, poster and splash images |
| `source/main.brs` | Entry point: opens `MainScene` |
| `components/MainScene.*` | Player, now-watching card, channel list, remote keys, saved state |
| `components/ChannelItem.*` | One row of the channel list |
| `components/OptionItem.*` | One row of the Picture & Sound panel |
| `components/ChannelLoader.*` | Task that downloads `channels.json` (network calls can't run on the UI thread) |
| `components/VariantLoader.*` | Task that reads a stream's master playlist to list its picture sizes |
| `images/` | Posters, splash screens and UI bitmaps. All of them are generated: don't edit them by hand |
| `images/labels/` | The Tamil channel names as images, plus `index.txt` listing them |
| `fonts/NotoSansTamil-Regular.ttf` | Font the Tamil labels are drawn with (SIL OFL, see `fonts/OFL.txt`). It is used only at build time and isn't in the zip |
| `art/render.ps1` | Regenerates `images/` with headless Chrome and Node |
| `components/SettingsScreen.*`, `SettingItem.*` | The Settings screen and its rows |
| `components/UpdateChecker.*` | Task that finds the newest `roku-v*` release on GitHub |
| `components/Common.brs` | Helpers shared by the scene and Settings (labels, saved sets, versions) |
| `art/labels.js` | Draws `images/labels/` from `channels.json` (run by the package scripts) |
| `art/icons.js`, `art/canvas.js` | Draw the tick boxes and gear from the Fire TV app's vector paths |
| `update.ps1` | Installs the newest release (or a given zip) on the Roku from a PC |
| `package.ps1`, `package.sh` | Build the sideload zip |

### Artwork

`powershell -ExecutionPolicy Bypass -File roku\art\render.ps1` renders these images:

- **Channel poster:** `poster_fhd.png` (540×405) and `poster_hd.png` (290×218), from
  `art/poster.html`, which uses the repository's `art/freeair-icon.svg`.
- **Splash screen:** `splash_fhd.png` (1920×1080) and `splash_hd.png` (1280×720), from the
  Fire TV banner, `art/banner.html`.
- **List header logo:** `logo_mark.png`.
- **UI bitmaps:** the script then runs `art/ui-assets.js` to draw the white focus pill and the
  card background (both 9-patch PNGs, since SceneGraph has no rounded rectangles) and the
  turmeric dots.

The colours match the Fire TV app: a black ground, a white focus pill, turmeric `#F2A93B`
for the accent, and the Aurora gradient in the icon.

### Tamil names

Roku's text engine doesn't apply Tamil shaping rules. With the font alone, vowel signs land on
the wrong side (புதிய showed as "பதிய"), so the channel shows Tamil names as images instead.
`art/labels.js` draws each `locale` from `channels.json` in Chrome (22 px for the list, 26 px
for the card), white on transparent, and the channel tints them. Each file is named after the
MD5 of its text. When a channel is added to `channels.json` after a build, the Roku loads its
label from GitHub (`roku/images/labels/` on `main`). So after editing names, run the package
script and commit the new labels.

## Tested on a Roku Premiere (3920X, Roku OS 15.3.4)

These work: sideloading, launch, playback, Up/Down, the channel list, the Picture & Sound
panel (Quality, Mute), Back, Tamil names, and the Settings screen (switching channels and languages on and off).

What I learned on the device:
- A list's focus 9-patch needs content guides (right and bottom edges) covering the whole image.
  Without them, Roku draws the pill larger than the row, over the rows next to it.
- A Scene can't take focus back from a child that has it, so an empty `keys` group holds focus
  while the picture is full screen.
- `roFileSystem` isn't allowed on the UI (render) thread.
- `*` is kept by Roku OS during playback.

Not yet checked:
- The Settings screen after the 0.1.6 spacing fix, and the update check against a real
  `roku-v*` release.
- How the layout looks on an HD (720p) Roku.
- Whether every channel plays.
- Whether audio through the HDMI audio extractor is better than on the Fire Stick.

## Later

Once it works when sideloaded, a **private beta channel** (Roku developer dashboard → Beta
channel, up to 20 testers by invite link) lets the Roku install it like a store channel, with
no developer mode. The public Channel Store would most likely reject a channel made of streams
it holds no licence for.
