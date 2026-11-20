# Car head-unit apps

Two small Android apps for an aftermarket car head unit (AC8227L, Android 10,
1024×600, OpenGL ES 2.0 only). Black-and-white, built for glancing at while driving.

| Folder | App | What it does |
|---|---|---|
| [`cardash/`](cardash) | **CarDash** (`com.moeez.cardash`) | Home-screen launcher: clock, Navigate and Music buttons, Bluetooth music screen with cover art, volume booster, opens the car's BT music app automatically on connect. |
| [`carnav/`](carnav) | **CarNav** (`com.moeez.carnav`) | Lightweight turn-by-turn navigation: OpenStreetMap tiles (English labels), Nominatim search (Pakistan), OSRM routing with rerouting, off-route and offline handling, trip recovery, and a mini music player and booster via CarDash. |

## How they fit together

- CarDash's **Navigate** button opens CarNav.
- CarNav's mini player talks to CarDash through a `ContentProvider` protected by
  a **signature-level** permission (`com.moeez.cardash.permission.HUB`). Both apps
  must be signed with the same key, and **CarDash must be installed first**.
  CarNav works fine without CarDash; the mini player is just hidden.

## Building

Requires JDK 17, Android Gradle Plugin 8.2 and Gradle 8.5 (pinned on purpose: newer
versions aren't needed and the head unit targets API 29).

```bash
cd carnav   # or cardash
gradle assembleRelease testDebugUnitTest
```

CarNav's release build is signed with the local Android **debug** keystore
(`~/.android/debug.keystore`) so it shares a key with CarDash. See
`carnav/app/build.gradle.kts`. No keystores or APKs are committed.

## Installing on the head unit

```bash
adb install -r cardash-v15.apk
adb install -r carnav-v0.4.apk
```

## Status

- CarNav v0.4: 52 unit tests, emulator-verified (see [`carnav/AUDIT.md`](carnav/AUDIT.md)). Not yet road-tested.
- Map data © OpenStreetMap contributors. Uses the public Nominatim and OSRM
  services, which allow light personal use only.
