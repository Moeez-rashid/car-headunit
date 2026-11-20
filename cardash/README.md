# CarDash

Minimal black/white home screen for the AC8227L head unit: clock + Navigate
button on idle, auto-switches to a Now Playing screen (title, artist,
progress bar, prev/pause/next) whenever Bluetooth AVRCP music is active.

## How to build

1. Install **Android Studio** (free): https://developer.android.com/studio
2. Open this folder as a project (`File > Open`, select the `CarDash` folder)
3. Let Gradle sync (Android Studio will auto-generate the Gradle wrapper files)
4. `Build > Build Bundle(s) / APK(s) > Build APK(s)`
5. Find the APK in `app/build/outputs/apk/debug/app-debug.apk`
6. Transfer it to the head unit (USB drive, or `adb install app-debug.apk`)
   and install it like you did with OpenLauncher

## First-run setup on the head unit

1. Open CarDash once
2. Tap **"Grant Notification Access"** — this is required because Android
   only lets apps read another app's media session (needed to see Bluetooth
   track info) if the user manually approves it in Settings. This is a
   one-time step.
3. Set CarDash as your default **Home app** when prompted (or via
   Settings > Apps > Default apps > Home app)

## Known TODOs before this is fully working

- **Confirm Organic Maps' real package name.** I used `app.organicmaps` as
  a placeholder — verify once installed with:
  `adb shell pm list packages | grep organicmaps`
  and update `ORGANIC_MAPS_PACKAGE` in `MainActivity.kt` if different.
- **Lotus Lantern (atmospheric lights).** The **Lights** button uses
  package `wl.smartled` (taken from the `uptodown-wl.smartled.apk`
  filename). Confirm on the unit after install with:
  `adb shell pm list packages | grep smartled`. If it differs, update
  `LOTUS_LANTERN_PACKAGE` in `MainActivity.kt`. Note: the car lights are a
  single-connection BLE device, so pairing them to the head unit means your
  iPhone won't control them at the same time.
- **Test whether the head unit's Bluetooth stack actually publishes a
  MediaSession** for the connected iPhone's AVRCP metadata. Most AOSP-based
  units do (that's literally how the stock lock screen shows track info),
  but this is unverified on your specific `com.autochips.bluetooth` stack.
  If `NowPlayingScreen` doesn't appear when playing music, this is the
  first thing to debug — try `adb shell dumpsys media_session` while music
  plays to see if a session shows up.
- No app icon set yet - using default Android icon for now.

## Reverting to stock launcher

Settings > Apps > Default apps > Home app > choose the original launcher.
CarDash is never uninstalled automatically, so this is always reversible.
