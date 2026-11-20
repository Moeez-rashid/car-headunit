# CarNav — QA and technical audit

**Build audited:** `carnav.apk` (sha256 `7a60eb61…3225`), `com.moeez.carnav` 0.1 (versionCode 1), built 2026-11-21.
**Date:** 2026-11-21. **App was not modified during the audit.**

## How it was tested

| Method | Used for |
|---|---|
| `aapt2`, `apksigner`, `dexdump`, `strings` on the APK | Manifest, SDKs, permissions, components, signing, class list, endpoints, secrets |
| Source review (same commit as the APK — class list in the DEX matches the source 1:1) | Logic, threading, lifecycle |
| 8 JUnit tests (`NavLogicTest`), re-run with `--rerun-tasks` | Route parsing, tracking, instruction text — **8/8 pass** |
| Emulator `carnav_headunit`: Android 10 (API 29) arm64, 1024×600 @ 160 dpi, like the head unit | All 15 scenarios below |
| `adb emu geo fix`, `svc wifi/data`, `settings … location_providers_allowed`, `am kill`, `dumpsys location`, logcat | GPS, network loss, GPS off, process death, location subscriptions |
| Live calls to OSRM on 4 real Lahore routes | Road-name language |

**Limits of the test environment.** Simulated GPS fixes carry no speed, bearing or accuracy, so speed display, heading from the GPS chip, and accuracy behaviour were not exercised. No real head unit, no real GPS chip, no real soft-keyboard typing (text was injected). The emulator's GPU supports OpenGL ES 3, so ES 2-only rendering is supported by design (osmdroid uses Canvas) but not *proven* on ES 2 hardware.

Status labels used: **Verified working**, **Verified broken**, **Potential issue (code inspection)**, **Not tested**, **Cannot determine from APK**.

---

## 1. Executive summary

CarNav's core loop works on an emulated head unit: map → search → route preview → turn-by-turn → reroute after a wrong turn → arrival, with no crashes in any scenario. No credentials are embedded, and every endpoint it actually calls uses HTTPS.

It is **not yet reliable enough to depend on for real driving**. Three problems matter most:

1. **43% of road names in turn instructions are in Urdu script** (22 of 51 named steps on 4 real Lahore routes), and most map labels are Urdu. This conflicts with the requirement that the app be in English.
2. **If GPS is off when CarNav starts, turning it on later never helps.** CarNav never subscribes and stays on "Waiting for GPS…" until restarted. Verified.
3. **When off route and the reroute fails (no internet), the banner keeps showing the old instruction and distance** as if still on route. Verified. That misleads a driver.

Navigation is also lost if Android kills the app in the background, location-permission denial leaves the app stuck on a misleading "Waiting for GPS…", and GPS readings are not filtered by accuracy.

## 2. Architecture overview

Single activity, Kotlin + Jetpack Compose, minSdk 24, targetSdk 29, compileSdk 34.

| Class (source file) | Role |
|---|---|
| `MainActivity` | Permissions, `LocationManager` (GPS + network, 1 Hz), back handling, keeps screen on |
| `NavModel` | All state (`mutableStateOf`) and behaviour: search, route, start/end, location handling, rerouting, recents |
| `Api` | `HttpURLConnection` + `org.json`: Nominatim search, OSRM routing (2 servers), pure parsers |
| `Route` / `RouteTracker` / `Progress` (`Route.kt`) | Route model, cumulative distances, map-matching of GPS fixes, next manoeuvre, remaining distance/time |
| `Instructions` | OSRM manoeuvre → English text + arrow type |
| `Geo` | Haversine, bearing, point-to-segment, formatting |
| `MapBinder` | osmdroid `MapView`, OSM tiles with greyscale-invert filter, route/position/destination overlays, pan detection |
| `UiKt` (`Ui.kt`) | Compose screens: browse, search panel, preview card, turn banner, trip bar |

Third-party code in the DEX: osmdroid (331 classes), androidx/Compose, kotlinx-coroutines. No analytics, no crash reporting, no WebView, no native code.

External services: `nominatim.openstreetmap.org` (search), `routing.openstreetmap.de` then `router.project-osrm.org` (routing), `tile.openstreetmap.org` (map tiles). No API keys.

## 3. APK inspection

| Item | Finding | Status |
|---|---|---|
| Package / version | `com.moeez.carnav`, 0.1 (1) | Verified |
| SDK | min 24, target 29, compile 34 | Verified |
| Permissions | INTERNET, ACCESS_NETWORK_STATE, ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION, plus androidx's signature-protected `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. No storage, no background location. Minimal. | Verified |
| Components | `MainActivity` (exported — launcher, required); `androidx.startup.InitializationProvider` (not exported); `androidx.profileinstaller.ProfileInstallReceiver` (exported but requires `android.permission.DUMP`, which normal apps can't hold) | Verified, acceptable |
| Activity config | `singleTask`, landscape, `adjustNothing`, `configChanges=0xff0` (keyboard, keyboardHidden, navigation, orientation, screenLayout, uiMode, screenSize, smallestScreenSize). **Not** locale, fontScale, density, layoutDirection. | Verified |
| `debuggable` | `true` | Verified (debug build) |
| `allowBackup` | `true` | Verified |
| Cleartext | No `usesCleartextTraffic`/network config; targetSdk 29 → cleartext blocked by default | Verified (manifest); not runtime-tested |
| Signing | Android Debug certificate, APK Signature Scheme v2 only (fine for minSdk 24) | Verified |
| Secrets | None. `MAPBOX_ACCESS_TOKEN`, `?token=` etc. are osmdroid's names/templates for tile providers CarNav never selects. | Verified |
| Endpoints | All 4 used endpoints HTTPS. `http://` URLs present only in osmdroid's unused tile-source catalogue (unreachable; would also be blocked by the cleartext default). | Verified |

## 4. Feature-by-feature inspection

### A. Maps
- **Initialisation and tiles: verified working.** Tiles load at 1024×600; the night filter renders; scroll limited to Pakistan (`MapBinder.init`).
- **Zoom and pan: verified working** (± buttons, pan disables follow, re-centre button). **Rotation: not supported** — north-up only, no rotation gesture overlay (by design, but a limitation).
- **Markers: verified working** (car arrow, destination ring, white route line with black casing).
- **Network failure: verified working for cached areas.** Previously viewed tiles still render offline. Uncached tiles show osmdroid's dark loading grid with no message (no explicit error state).
- **Labels are mostly Urdu: verified.** The standard OSM style renders the local `name` tag (see H1).
- Tile cache lives in `cacheDir`, which Android may purge under storage pressure (`MapBinder.init`, `osmdroidTileCache`). Potential issue.

### B. GPS and location
- **Permission request: verified working** (Android 10 dialog on first launch).
- **Denial: verified broken (UX)** — see M2.
- **Current location: verified working** with simulated fixes.
- **Update rate:** 1 s, 0 m for GPS and network (`MainActivity.startLocation`, line 91). Adequate for driving.
- **GPS disabled at start, enabled later: verified broken** — see H2.
- **Approximate vs precise:** Android 10 has no approximate toggle; FINE and COARSE are granted together by the dialog. If only COARSE were granted (adb/MDM), `requestLocationUpdates(GPS_PROVIDER)` would throw `SecurityException` and crash — potential issue (L4).
- **Background:** the subscription stays active in the background (`dumpsys location` shows `com.moeez.carnav … background … Currently active`) and navigation state survives minimise/return. Delivery of fixes while backgrounded wasn't isolated.
- **Battery/power:** GPS runs at 1 Hz from launch until `onDestroy`, including on the browse screen and in the background (`MainActivity.onDestroy`, line 74). Low impact on car power, but wasted work (L5).
- **No accuracy filtering:** `Location.accuracy` is never read (`MainActivity.onFix`). See M1.

### C. Search (Nominatim)
- **Search: verified working** ("Emporium Mall Lahore" → English result, distance shown).
- **Query construction: verified** — `countrycodes=pk`, `accept-language=en`, `format=jsonv2`, `limit=10`, `viewbox` ±0.7° around the car with `bounded=0` (`Api.search`, line 36). Coordinates in correct lon/lat order for `viewbox`.
- **Debouncing:** not applicable — search runs only on GO, which is what Nominatim's policy requires (no autocomplete). There is no client-side throttle if GO is pressed repeatedly (L2).
- **Loading / empty / error states: verified working** — "Searching…", "No places found…", and offline "Couldn't search. Check the internet connection." The error toast vanishes after 4 s and the panel falls back to the generic hint (L6).
- **Malformed JSON:** `JSONException` is caught by the generic `catch` in `NavModel.submitSearch` → same error toast. Potential (code inspection; not injected).
- **Timeouts:** 10 s connect / 15 s read (`Api.get`, lines 65–66).
- **Policy compliance:** identifying User-Agent set (`Api.USER_AGENT`), submit-only searches. Acceptable for one user.
- **Selecting a result: verified working** — preview card, route drawn, map framed around the route clear of the card.
- **Search box bug: verified** — see L1.

### D. Routing (OSRM)
- **Origin/destination: verified correct** — `lon,lat;lon,lat` order (`Api.route`, line 48); routes in the emulator went from the car to the chosen place.
- **Geometry: verified** — GeoJSON geometry parsed lon/lat → `LatLon(lat, lon)` (`Api.parseRoute`); unit test confirms measured length within 3% of OSRM's distance.
- **Distance/duration: verified** — metres and seconds, formatted correctly (unit tests).
- **Alternatives: not supported** (not requested; no UI).
- **No route:** `code != "Ok"` → `IllegalStateException("No driving route found")`, then the fallback server is tried, then `routeError` shows the message with "Try again". Code inspection only — couldn't make OSRM return `NoRoute` from inside Pakistan.
- **Failures/timeouts:** caught → "Couldn't get a route. Check the internet connection." + Try again. Worst case ≈ 2 servers × (10 + 15) s = 50 s before failing (M4).
- **Road-name language: verified problem** — see H1.

### E. Turn-by-turn
- **Start, tracking, next manoeuvre, countdown, remaining time and distance, arrival time: verified working** on a simulated drive (Liberty Market → Emporium Mall) and in the unit test `trackerFollowsASimulatedDrive`.
- **Instruction text: verified** for every real step type in the fixture (`everyRealStepGetsSensibleText`, `instructionWording`).
- **Timing:** the banner moves to the next manoeuvre once the car is within 5 m of the current one (`RouteTracker.update`). With 1 Hz fixes at 50 km/h (~14 m/s) the last shown distance before a turn is ~10–20 m. No "then…" preview of the following turn.
- **ETA:** `route.duration × remaining/total` — no traffic, assumes OSRM's average speeds.
- **Voice:** not implemented (by design; the unit has no TTS engine).
- **Arrival: verified working**, but can fire early — see M3.
- **Self-overlapping routes:** potential instruction skip/repeat — see M5.

### F. Rerouting
- **Detection and reroute: verified working** — 3 consecutive fixes > 45 m off the line, at most one reroute per 10 s (`NavModel.onLocation`, line 150). New route starts from the current position; banner and line update.
- **Single GPS glitch: verified tolerated** — one fix 300 m away did not reroute; instructions continued.
- **Offline: verified** — "Couldn't reroute — will try again." It retries every 10 s while still off route and recovered once the connection returned. But the stale instruction stays on screen meanwhile (H3), and "Rerouting…" can hang for up to ~50 s (M4).

### G. Stability
- **No crashes** in any scenario (logcat checked after each).
- **Threading:** network on `Dispatchers.IO`; state written on the main thread from `lifecycleScope`. Sound.
- **Cancellation swallowed:** see M6.
- **Lifecycle:** minimise/return verified working. **Process death verified to lose navigation** (M7). Activity recreation on locale/font/density change would also reset state (L3, potential).
- **Rotation:** locked to landscape; orientation is in `configChanges`. Not applicable.

## 5. Real-world scenarios

| # | Scenario | Result | Evidence |
|---|---|---|---|
| 1 | First launch | **Verified working** | Permission dialog "Allow CarNav to access this device's location?" |
| 2 | Location permission denied | **Verified broken (UX)** | Toast for 4 s, then permanent "Waiting for GPS…"; no way to re-request (M2) |
| 3 | GPS disabled | **Verified broken** | Turning GPS on while running → no subscription in `dumpsys location`; works only after restart (H2) |
| 4 | Internet lost | **Verified working** | Search: clean error. Cached map still shows. |
| 5 | Search for a destination | **Verified working** | English result + distance |
| 6 | No results | **Verified working** | "No places found. Try a different spelling or add the city name." |
| 7 | Select destination, get directions | **Verified working** | 15 min · 11 km preview, route framed |
| 8 | No route possible | **Potential (code inspection)** | Message + Try again path exists; not reproducible in emulator |
| 9 | Follow the route | **Verified working** | Instructions/countdown/remaining update as car moves |
| 10 | Wrong turn | **Verified working** | Reroute from current position within ~3 s |
| 11 | Inaccurate GPS | **Partly verified** | Single 300 m glitch tolerated; camera jumps to it (M1). Sustained poor accuracy not testable with emulator fixes. |
| 12 | Internet lost while navigating | **Verified, with a defect** | Reroute fails gracefully and recovers, but the old instruction stays on screen (H3) |
| 13 | Minimise during navigation | **Verified working** | GPS subscription kept in background |
| 14 | Return to app | **Verified working**; **broken after process death** | State intact after minimise; lost after `am kill` (M7) |
| 15 | Reach destination | **Verified working** | "You've arrived · Emporium Mall" + DONE (tested on the build just before this one; arrival code unchanged). Early-arrival risk: M3 |

## 6. Critical and high-priority issues

No **CRITICAL** issues: no exposed secrets, nothing makes navigation unusable outright.

### H1 — Road names in turn instructions (and map labels) are often in Urdu
- **Severity:** HIGH (the app is required to be in English)
- **Where:** `Api.parseRoute` (`name = st.optString("name")`), `Instructions.roadName`, `Instructions.text`; map: `MapBinder` uses `TileSourceFactory.MAPNIK`.
- **Evidence:** 22 of 51 named steps on 4 real Lahore OSRM routes contain Arabic-script names, e.g. `مال روڈ` (Mall Road), `ڈیوس روڈ` (Davis Road), `نہر سڑک` (Canal Road), `Empress Road ایمپریس روڈ`. Map screenshots show mostly Urdu labels.
- **Trigger:** any route over roads whose OSM `name` tag is Urdu.
- **Expected:** "Turn left onto Mall Road". **Actual:** "Turn left onto مال روڈ".
- **Fix:** when a name mixes scripts, keep the Latin part; when it is Urdu only, use the road number (`ref`) or transliterate to Roman Urdu with a road-word dictionary (روڈ→Road, سڑک→Road, چوک→Chowk…). For the map, switch to a tile style that prefers Latin-script names.

### H2 — GPS turned on after launch is never used
- **Severity:** HIGH
- **Where:** `MainActivity.startLocation`, lines 85–91: `lm.getProviders(true)` returns only *enabled* providers, and `requestLocationUpdates` is called only for those.
- **Reproduce:** turn location off → open CarNav → turn location on.
- **Expected:** position appears within seconds. **Actual:** "Waiting for GPS…" forever; `dumpsys location` shows no CarNav request until the app restarts. Verified.
- **Fix:** request updates for `GPS_PROVIDER` whenever it exists, enabled or not (Android delivers fixes once it's switched on), and/or listen for `LocationManager.PROVIDERS_CHANGED_ACTION`.

### H3 — Stale instruction shown while off route and rerouting fails
- **Severity:** HIGH (misleading directions while driving)
- **Where:** `NavModel.onLocation` (progress keeps updating from the old route), `UiKt.TurnBanner` (no off-route state).
- **Reproduce:** navigating → internet off → leave the route.
- **Expected:** "Off route — reconnecting…" with the old instruction hidden. **Actual:** "1.7 km · Continue onto Khayaban-e-Jamia Punjab" stays up while the car is ~300 m away and heading elsewhere; only a 4 s toast says the reroute failed. Verified (screenshots at 6 s and 14 s).
- **Fix:** add an `offRoute` state (e.g. `offRoute > 45 m` for ≥ 3 fixes); while set, replace the banner with an off-route message and keep retrying.

## 7. Medium and low-priority issues

### MEDIUM

**M1 — No GPS accuracy filtering.** `MainActivity.onFix` never checks `Location.accuracy` or `time`. *Evidence:* a single bad fix moved the camera 300 m (verified); network-provider fixes (hundreds of metres) are fed in whenever GPS has been silent for 5 s (line 102). Sustained poor accuracy (urban canyon, tunnel) could cause false reroutes from wrong positions. *Fix:* drop fixes with accuracy > ~50 m during navigation, and never use network fixes for route tracking.

**M2 — Permission denial leaves a misleading dead end.** `MainActivity` permission callback (line 30) shows a 4 s toast; afterwards `UiKt.CarNavApp` shows "Waiting for GPS…" (line 118) forever. Verified. *Fix:* permanent "Location permission needed" card with a button that re-requests or opens app settings.

**M3 — Early "You've arrived" when the route passes near the destination.** `RouteTracker.update`, Route.kt line 100: `arrived = remaining < 30 || distance(p, last point) < 25`. On divided roads (common in Lahore; the test route itself contains a U-turn step) the route can pass within 25 m of the destination on the opposite carriageway before the U-turn. Potential (code inspection). It self-corrects on the next fix, but flickers "You've arrived" mid-route. *Fix:* use only `remaining < 30`.

**M4 — Reroute/route can stall up to ~50 s.** `Api.get` timeouts 10 s + 15 s, tried on 2 servers sequentially (`Api.route`). Banner shows only "Rerouting…" meanwhile. Partly observed after reconnecting. *Fix:* shorter timeouts (e.g. 6 s / 8 s) during navigation; keep showing distance to destination.

**M5 — Tracker can jump to another part of a self-overlapping route.** `RouteTracker.update`, Route.kt lines 81–84: if the best match in the window is > 60 m away it searches the whole route and may pick an earlier or later section (U-turns, loops), skipping or repeating instructions. Potential (code inspection). *Fix:* when doing the full search, prefer matches ahead of the last position and whose direction matches the car's heading.

**M6 — Cancellation treated as an error.** `NavModel` catches `Exception` (lines 66, 103, 166), which includes `CancellationException`. Pressing Cancel/End during a request later writes `routeError` / "Couldn't reroute — will try again." *after* the state was reset. If the next destination is chosen before GPS has a fix, the stale error is shown and blocks the automatic route request (`onLocation` checks `routeError == null`). Potential (code inspection). *Fix:* rethrow `CancellationException` before the generic catch.

**M7 — Navigation lost if Android kills the app in the background.** No persisted navigation state and no foreground service. Verified with `am kill`: reopening shows "Where to?" with no destination. *Fix:* persist destination + mode in `SharedPreferences` and resume (re-route) on start; optionally a foreground service during navigation.

**M8 — Stale last-known location used as route origin.** `MainActivity.startLocation` seeds the model with `getLastKnownLocation` regardless of age. If the car moved since (e.g. towed, or the unit cached a far fix), the first route starts from the wrong place until a reroute. Potential. *Fix:* ignore last-known fixes older than ~2 minutes for routing.

### LOW

- **L1 — Search box keeps the old query with the cursor at the start; typing is inserted before it.** `UiKt.SearchPanel` (`BasicTextField(value = model.query …)`, line 197). Verified ("Lahore Fort" + "xqzvbnmkk"). *Fix:* clear the query on open or use `TextFieldValue` with the cursor at the end.
- **L2 — No client-side rate limit on GO.** `NavModel.submitSearch` only blocks while a request is in flight; repeated GO taps can exceed Nominatim's 1 request/second. Potential. *Fix:* ignore GO within 1.1 s of the previous search.
- **L3 — Activity recreation resets everything.** `configChanges` lacks `locale|fontScale|density|layoutDirection`; `NavModel` and `MapBinder` live in the activity. Potential (not tested). *Fix:* add those flags, or hold state in a `ViewModel`.
- **L4 — Crash if only COARSE location is granted.** `requestLocationUpdates(GPS_PROVIDER)` needs FINE. Not reachable through the Android 10 dialog. Potential. *Fix:* request GPS only when FINE is granted.
- **L5 — GPS always on at 1 Hz, even on the browse screen and in the background.** Verified via `dumpsys`. *Fix:* stop updates in `onStop` unless navigating.
- **L6 — Error toasts disappear after 4 s.** `UiKt.CarNavApp` line 82. Verified (offline search). Fine for transient errors; not for persistent ones (see M2).
- **L7 — Debug build:** `debuggable=true`, debug-signed. Fine for personal sideloading; use a release build if it's ever shared.
- **L8 — Map tile cache can be purged** (in `cacheDir`). Potential.

## 8. Security and privacy

| Check | Result |
|---|---|
| Hardcoded API keys/credentials | **None** (verified DEX string scan) |
| Insecure HTTP | **None used.** All 4 endpoints HTTPS; osmdroid's unused `http://` tile templates are unreachable |
| Sensitive data storage | Recent destinations (name + coordinates) in `SharedPreferences` `carnav/recents`, unencrypted, included in backups because `allowBackup=true`. Low risk on a personal head unit; set `allowBackup=false` if wanted |
| Excessive permissions | No. Only network + location |
| Location data handling | Sent to third parties by design: Nominatim gets a ±0.7° box around the car with each search; OSRM gets exact origin and destination; the OSM tile server sees which areas are viewed. Nothing else; no analytics or logging of location |
| Exported components | Launcher activity only (required); profileinstaller receiver is protected by `DUMP` |
| Debuggable | Yes (debug build) — L7 |
| WebView | Not used |
| Network config | Platform default for targetSdk 29 (cleartext blocked) |

## 9. Performance and battery

- Map rendering uses Canvas (no OpenGL ES 3), the key requirement for this unit. Smoothness on the real AC8227L **not tested**; the emulator is much faster.
- Polling: none; GPS callbacks at 1 Hz drive everything. The tracker searches ~80 segments per fix — negligible.
- `Route.stepAlong` is computed once per route (O(points × steps) worst case) — negligible for city routes.
- GPS stays on when not needed (L5). Car power makes battery a non-issue; CPU/radio use is still avoidable.
- Debug build, unminified (15 MB `classes.dex`); a release build would start faster.

## 10. Missing or incomplete functionality

- English road names and map labels (H1)
- Off-route state in the UI (H3)
- Resume navigation after process death (M7)
- "Then …" preview of the following manoeuvre
- Alternative routes
- Heading-up map rotation
- Lane guidance, speed limits, traffic
- Voice guidance (the unit has no TTS engine)
- Saved places (Home/Work) beyond recents

## 11. Recommended fixes, in priority order

1. **H1** — English road names in instructions; Latin-script map labels.
2. **H3** — Off-route banner state; never show stale instructions.
3. **H2** — Subscribe to GPS even when it's off at launch.
4. **M1** — Accuracy filtering; no network fixes during navigation.
5. **M2** — Permanent permission prompt with a re-request button.
6. **M3** — Arrival only by remaining distance.
7. **M7** — Persist and resume navigation.
8. **M4, M6, M5, M8** — Shorter navigation timeouts; rethrow cancellation; direction-aware full search; ignore stale last-known fixes.
9. **L1–L8.**

## 12. Overall readiness

**Usable for a supervised trial drive, not yet dependable.** The main flow works end to end on an emulated Android 10 head unit with no crashes, the logic is unit-tested against real Lahore data, and there are no security problems. Before relying on it, fix H1–H3 and M1–M2. Then do a real-car test, which is the only way to check rendering speed on the ES 2 GPU, real GPS accuracy and heading, and soft-keyboard search.

## Summary table

| Feature | Status | Severity | Evidence |
|---|---|---|---|
| Maps | Verified working (tiles, zoom, pan, markers, offline cache); labels mostly Urdu; no rotation | HIGH (labels, H1) | Emulator screenshots; `MapBinder` |
| GPS | Verified working with GPS on; **verified broken** if GPS enabled after launch; no accuracy filter | HIGH (H2), MEDIUM (M1, M2) | `dumpsys location`; `MainActivity.startLocation` / `onFix` |
| Search | Verified working (results, empty, offline); search-box cursor bug | LOW (L1, L2) | Emulator; `Api.search` |
| Routing | Verified working (correct order, geometry, distance/time); 43% Urdu road names | HIGH (H1), MEDIUM (M4) | Emulator; unit tests; 4 live OSRM routes |
| Navigation | Verified working (instructions, countdown, ETA, arrival); early-arrival and overlap risks | MEDIUM (M3, M5) | Simulated drive; `RouteTracker.update` |
| Rerouting | Verified working online and recovers offline; stale instruction while failing | HIGH (H3) | Screenshots during offline wrong turn |
| Background operation | Verified working for minimise/return; **verified broken** after process death | MEDIUM (M7) | `dumpsys location`; `am kill` test |
| Security | No secrets, HTTPS only, minimal permissions; debug build, backups on | LOW (L7) | aapt2/apksigner/DEX scan |
| Stability | No crashes observed in 15 scenarios; cancellation swallowed; recreation resets state | MEDIUM (M6), LOW (L3, L4) | Logcat after each scenario; `NavModel` |

---

## Post-audit change (same day): H1 fixed in a newer build

At the user's request, after this audit:

- **Map labels:** switched to OpenStreetMap's German community style (`tile.openstreetmap.de`), which gives every label a Latin-script name (e.g. "Mall Rd. – مال روڈ", "Shadman", "Lahore Cant"). Verified on the emulator. The standard and French styles were compared and rejected (Urdu-only labels; empty boxes for Urdu names, respectively).
- **Turn instructions:** new `Names` converts Urdu road names to English offline (keep the English part of mixed names; dictionary + Roman-Urdu spelling otherwise). In the background, `NavModel.resolveRoadNames` looks up each Urdu-named road's official English name via Nominatim reverse geocoding (≤ 1 request/second through a shared gate in `Api`), accepts it only if it plausibly matches (`Names.plausible`), and caches it on the device. Verified on the emulator: "Turn right onto Mozang Road".
- **Tests:** 17 unit tests, all passing (8 original + 9 for names: 18 real Lahore Urdu road names, 15 real lookups accepted, one wrong-road lookup rejected).
- **Side effect:** all Nominatim calls are now rate-limited to 1 per second, which also fixes **L2**.

All other findings above still apply to the newer build.

---

## Phase 2 (2026-11-21): reliability fixes — build 0.2 (`carnav-v0.2.apk`)

| Finding | Status in 0.2 | Verified by |
|---|---|---|
| H2 GPS enabled after launch | **Fixed** — subscribes to GPS even while off, listens for provider changes, one subscription only | Emulator: launch with location off → enable → fixes arrive, same PID; toggled off/on, no crash; 1 listener in `dumpsys location` |
| (new) Android 10 listener crash | **Fixed** — full `LocationListener` object (lambda would throw `AbstractMethodError` on provider on/off once H2 subscribes to disabled providers) | Emulator toggle test |
| H3 stale instruction while off route | **Fixed** — explicit `offRoute` state; banner "Off route · Finding a new route… / Waiting for internet connection…"; retries 5→10→20 s and immediately when the network returns | Unit `OffRouteTest`; emulator offline deviation → banner, reconnect → new route in ~2 s |
| M1 accuracy filtering | **Fixed** — `FixFilter`: stale > 10 s, accuracy > 50 m (navigating) / 250 m (map), network never used for navigation, impossible jumps rejected unless 3 agree | Unit `FixFilterTest`, `OffRouteTest`; emulator 400 m glitch → map unchanged (accuracy/stale only unit-tested: emulator can't fake them) |
| M2 permission denial | **Fixed** — card with "Allow location", or "Open settings" after "Don't ask again"; rechecked on return | Emulator: deny → deny & don't ask again → Settings grant → recovered without restart |
| M3 early arrival | **Fixed** — arrival only by remaining route distance; progress can't advance faster than the car could drive; latched once | Unit `ArrivalTest`; emulator on real Main Blvd Gulberg U-turn (passes 23 m from destination with 1.6 km left: no arrival; arrives at the end once) |
| M7 trip lost on process death | **Fixed** — trip saved on Start, cleared on End/arrival; on reopen resumes and routes from a **live** fix (cached last-known position ignored while navigating); corrupt/old (> 6 h) state discarded | Unit `TripRecoveryTest`; emulator `am kill` + moved car → resumed on new route from new position |
| M6 cancellation swallowed | **Fixed** (needed for the new retry logic) | Code |
| L4 COARSE-only crash | **Fixed** — only FINE counts as granted | Code |
| L5 GPS always on | **Improved** — unsubscribes when backgrounded unless navigating | Code |

**Foreground service: not added.** Directions are on-screen only, so background tracking has no user value; Android 8+ already throttles background location for apps without one. Trip persistence (M7) plus resume-on-return covers the real risk. Revisit if voice guidance is ever added.

Unit tests: 32/32 pass (17 from H1 + 15 new). Still open from the audit: M4 (long timeouts), M5 (self-overlapping routes — partly mitigated by the advance limit), M8 (superseded by the live-fix rule while navigating), L1, L3, L6–L8.

---

## Phase 3 (v0.4, 2026-11-21): reliability, security and release

### Fixes
- **M4 — routing time limits.** `Api.route` now has two profiles. Preview allows 15 s per server attempt. Navigation reroutes use `RouteProfile.NAVIGATION`: 4 s connect, 6 s read, 7 s per attempt, plus a hard 16 s limit in `NavModel` across both servers. Requests can now actually be cancelled: cancelling the coroutine closes the connection. Previously an abandoned request kept blocking until the timeouts ran out.
  - "No route" is now told apart from connectivity problems. OSRM answers `NoRoute`/`NoSegment` with **HTTP 400**, which the old code threw away as "HTTP 400". It now raises `NoRouteException`: no retry on the fallback server, a separate banner, and a 30 s retry. Network, timeout, 5xx and 429 failures raise `RoutingUnavailableException`: fallback, then 5/10/20 s back-off, plus an immediate retry when the network returns.
  - Duplicate requests are blocked by checking whether the request job is still active, and a request id stops a cancelled older request from clearing the newer one's "rerouting" flag.
  - While no route is usable, the trip bar shows the straight-line distance to the destination.
  - Reroutes pass the car's GPS heading to OSRM as `bearings=h,60;` when it is reliable (speed ≥ 2.5 m/s, heading under 5 s old), so the new route starts in the direction of travel. That is the correct side of a divided road. If OSRM can't match the heading, the request is retried once without it.
- **M5 — map matching.** `RouteTracker.update` now scores every segment as distance plus penalties:
  - **Ahead limit:** a hard cap on how far ahead progress can jump, based on time since the last on-route fix.
  - **Backward penalty:** 40 m plus 0.1 m per metre beyond 25 m of tolerance.
  - **Far-ahead penalty:** 0.1 m per metre.
  - **Heading penalty:** up to 80 m when the GPS heading is reliable and the segment points the other way.

  While the car is more than 50 m from the line, progress stays where it left the route. A new `wrongWay` flag (heading more than 135° off the route) counts as off-route evidence. Fixes without a heading neither add to nor clear that count, so the car is rerouted in its own direction.
- **L1 — search box:** `TextFieldValue` with the previous query fully selected, plus a × clear button. Old results are cleared when a new search starts.
- **L3 — recreation:** state lives in `CarNavViewModel`. `restoreTrip()` runs only when the ViewModel is first created, and the permission prompt only on a fresh start. `configChanges` adds `fontScale|locale|layoutDirection`. The map view is detached in `onDestroy`.
- **L6 — messages:** search errors stay in the search panel until the next search. Toast messages last 8 s and can be tapped away.
- **L7 — release build:** `assembleRelease` produces a non-debuggable build, signed with the same key as CarDash (see the note in build.gradle.kts). `allowBackup=false` and `usesCleartextTraffic=false`.
- **L8 — tile cache:** moved from `cacheDir`, which Android purges, to `filesDir/tiles`, capped at 300 MB and trimmed to 250 MB. The v0.3 cache directory is deleted.

### The `com.moeez.cardash.permission.HUB` permission
- It is a custom permission declared by **CarDash** (`com.moeez.cardash`, `protectionLevel="signature"`). CarNav requests it in its own `AndroidManifest.xml`; no library adds it.
- It was added in v0.3 for the mini music player and booster. `MusicHub.kt` calls CarDash's `HubProvider` (`content://com.moeez.cardash.hub`) for track info, play/pause/next/previous, and setting the booster level. The provider is exported but protected by this permission.
- Removing it would make those calls fail with SecurityException, and the mini player would disappear. Navigation does not depend on it. This was verified with CarDash uninstalled: the mini player was hidden and navigation still worked.
- It is kept and now documented in the manifest.
- The other entries in the merged manifest come from AndroidX and are standard:
  - `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`
  - a non-exported `androidx.startup` provider
  - the profileinstaller receiver, which requires `DUMP`

### Remaining
- `router.project-osrm.org` is a CNAME for `routing.openstreetmap.de`, so both are the same server, `5.148.170.168`. The fallback covers a failure on one URL path, but not that server being down.
- Inaccurate-fix rejection (accuracy over 50 m) is unit-tested only. The emulator always reports 20 m accuracy.
- L2, L4 and L5 were handled earlier. M8 is superseded.
- Not yet tested in the real car.

Unit tests: **52/52 pass** (32 existing + 20 new in `Phase3Test.kt`).
