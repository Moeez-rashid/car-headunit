# CarNav

A small, fast navigation app for the AC8227L car head unit (Android 10, OpenGL ES 2.0 only).
Launched from CarDash's Navigation button.

- **Map:** osmdroid (plain Canvas, no OpenGL ES 3) showing standard OpenStreetMap tiles in
  black-and-white night colours. Tiles are cached (up to ~500 MB), so roads you've driven
  stay visible without internet.
- **Search:** OpenStreetMap Nominatim, Pakistan only, English names, searched on GO
  (Nominatim's rules don't allow search-as-you-type). Recent destinations are remembered.
- **Routes:** OSRM car routing (the FOSSGIS server openstreetmap.org uses, with the OSRM demo
  server as fallback). Reroutes after ~3 s off the route.
- No API keys. All three services ask for light, personal use with an identifying User-Agent.
- Directions are on screen only — the head unit has no text-to-speech engine.

## Build

Same pinned toolchain as CarDash: AGP 8.2.0 + Gradle 8.5 + JDK 17.

```bash
JAVA_HOME=~/Library/Java/JavaVirtualMachines/corretto-17.0.20/Contents/Home \
  ~/.gradle/wrapper/dists/gradle-8.5-bin/*/gradle-8.5/bin/gradle -p . assembleDebug testDebugUnitTest
```

Unit tests (`NavLogicTest`) run the route tracker and instruction text against a real
Lahore route saved in `app/src/test/resources`.

An emulator set up like the head unit (Android 10, 1024×600, 160 dpi) exists as AVD
`carnav_headunit`. Simulate driving with `adb emu geo fix <lon> <lat>`.

## Install on the head unit

```bash
adb install -r carnav.apk
adb shell pm grant com.moeez.carnav android.permission.ACCESS_FINE_LOCATION
```
