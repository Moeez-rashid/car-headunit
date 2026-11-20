package com.moeez.carnav

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.roundToInt

enum class Mode { BROWSE, SEARCH, PREVIEW, NAVIGATE }

/** Location permission as the UI needs it (audit finding M2). */
enum class LocationPermission { GRANTED, NEEDED, BLOCKED }

/** Tiny key-value store, so the model can be unit-tested without Android. */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

/**
 * All app state + behaviour. Compose observes the mutableStateOf fields.
 * Dependencies are injectable so the navigation logic can be tested on the JVM.
 */
class NavModel(
    private val scope: CoroutineScope,
    private val store: KeyValueStore,
    private val router: suspend (LatLon, LatLon) -> Route = { a, b -> Api.route(a, b) },
    /** Route while driving: short time limits, starts in the car's direction (M4). */
    private val rerouter: suspend (LatLon, LatLon, Float?) -> Route = { a, b, _ -> router(a, b) },
    private val nameLookup: suspend (LatLon) -> String? = Api::reverseRoad,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val wallClock: () -> Long = { System.currentTimeMillis() }
) {

    var mode by mutableStateOf(Mode.BROWSE)
    var location by mutableStateOf<LatLon?>(null)
    var speedKmh by mutableStateOf<Int?>(null)
    var heading by mutableStateOf(0f)
    var following by mutableStateOf(true)

    // Location availability (H2, M2).
    var permission by mutableStateOf(LocationPermission.GRANTED)
    var gpsEnabled by mutableStateOf(true)

    var query by mutableStateOf("")
    var results by mutableStateOf<List<Place>>(emptyList())
    var searched by mutableStateOf(false)
    var recents by mutableStateOf(loadRecents())

    var destination by mutableStateOf<Place?>(null)
    var route by mutableStateOf<Route?>(null)
    var progress by mutableStateOf<Progress?>(null)

    /** A route request is in flight while navigating (reroute or trip resume). */
    var rerouting by mutableStateOf(false)
    /** Confirmed off the route: the banner must not show the old instructions (H3). */
    var offRoute by mutableStateOf(false)
    /** The last attempt to get a new/resumed route failed (usually no internet). */
    var rerouteFailed by mutableStateOf(false)
    /** …and the reason was a genuine "no route from here", not connectivity (M4). */
    var rerouteNoRoute by mutableStateOf(false)
    /** Reached the destination. Latched: announced once, never "un-arrives" (M3). */
    var arrived by mutableStateOf(false)
    /** Navigating a trip restored after the app was closed; needs a fresh route (M7). */
    var resuming by mutableStateOf(false)

    var busy by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var routeError by mutableStateOf<String?>(null)
    /** Search failure, shown in the search panel until the next search (L6). */
    var searchError by mutableStateOf<String?>(null)

    /** Straight-line distance to the destination, shown while no route is usable (M4). */
    val directDistance: Double?
        get() {
            val here = location ?: return null
            val d = destination ?: return null
            return Geo.distance(here, d.location)
        }

    private val fixFilter = FixFilter()
    private var lastFixAccuracy: Float? = null
    private var tracker: RouteTracker? = null
    private var lastTrackedAt: Long? = null
    private var offRouteFixes = 0
    /** Fixes driving against the route; a fix with no usable heading neither adds nor clears. */
    private var wrongWayFixes = 0
    /** Car heading from GPS, only when it is trustworthy (moving), and when it was taken. */
    private var reliableBearing: Float? = null
    private var reliableBearingAt = 0L
    private var routeRequestId = 0
    private var onRouteFixes = 0
    private var retryDelayMs = FIRST_RETRY_MS
    private var nextRetryAt = 0L
    private var lastFix: LatLon? = null
    private var job: Job? = null
    private var navJob: Job? = null

    // Official English names for Urdu-named roads, looked up once and kept on
    // the unit. A snapshot map, so instructions recompose when a name arrives.
    private val roadNames = mutableStateMapOf<String, String>().apply { putAll(loadRoadNames()) }
    private val rejectedNames = HashSet<String>()
    private var namesJob: Job? = null

    init {
        Instructions.resolvedName = { roadNames[it] }
    }

    /** Look up English names for this route's Urdu-only roads, in driving order. */
    private fun resolveRoadNames(r: Route) {
        namesJob?.cancel()
        namesJob = scope.launch {
            for ((k, step) in r.steps.withIndex()) {
                val key = Names.key(step.name)
                if (!Names.isUrduOnly(key) || key in roadNames || key in rejectedNames) continue
                // Probe a point a little way along the new road, clear of the junction.
                val probe = r.pointAt(r.stepAlong[k] + minOf(40.0, step.distance / 2))
                val found = try {
                    nameLookup(probe)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null // offline: keep the offline conversion, try again next route
                } ?: continue
                if (Names.plausible(Names.english(key), found)) {
                    roadNames[key] = found
                    saveRoadNames()
                } else {
                    rejectedNames += key // landed on a different road; don't ask again
                }
            }
        }
    }

    private fun saveRoadNames() {
        val o = JSONObject()
        for ((k, v) in roadNames) o.put(k, v)
        store.putString("road_names", o.toString())
    }

    private fun loadRoadNames(): Map<String, String> = try {
        val o = JSONObject(store.getString("road_names") ?: "{}")
        o.keys().asSequence().associateWith { o.getString(it) }
    } catch (e: Exception) {
        emptyMap()
    }

    // ---- Search -------------------------------------------------------------

    fun openSearch() {
        mode = Mode.SEARCH
        results = emptyList()
        searched = false
        searchError = null
    }

    fun closeSearch() {
        mode = if (route != null) Mode.PREVIEW else Mode.BROWSE
    }

    fun submitSearch() {
        val q = query.trim()
        if (q.length < 2 || busy) return
        busy = true
        searchError = null
        // Old results under a new query (or under an error) would be misleading.
        results = emptyList()
        searched = false
        job = scope.launch {
            try {
                results = Api.search(q, location)
                searched = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                searchError = "Couldn't search. Check the internet connection and press GO to try again."
            } finally {
                busy = false
            }
        }
    }

    fun choose(place: Place) {
        destination = place
        addRecent(place)
        route = null
        progress = null
        routeError = null
        mode = Mode.PREVIEW
        following = false
        computeRoute()
    }

    // ---- Route preview ------------------------------------------------------

    fun computeRoute() {
        val dest = destination ?: return
        val from = location
        if (from == null) {
            routeError = null
            message = "Waiting for GPS — the route will appear once your position is known."
            return
        }
        busy = true
        routeError = null
        job?.cancel()
        job = scope.launch {
            try {
                installRoute(router(from, dest.location), from)
            } catch (e: CancellationException) {
                throw e
            } catch (e: NoRouteException) {
                routeError = "No driving route to this place."
            } catch (e: Exception) {
                routeError = "Couldn't get a route. Check the internet connection."
            } finally {
                busy = false
            }
        }
    }

    private fun installRoute(r: Route, from: LatLon) {
        route = r
        tracker = RouteTracker(r)
        lastTrackedAt = null
        progress = tracker?.update(from)
        resolveRoadNames(r)
    }

    // ---- Navigation ---------------------------------------------------------

    fun start() {
        if (route == null) return
        mode = Mode.NAVIGATE
        following = true
        resetNavState()
        saveTrip()
    }

    fun end() {
        job?.cancel()
        navJob?.cancel()
        namesJob?.cancel()
        mode = Mode.BROWSE
        destination = null
        route = null
        progress = null
        tracker = null
        busy = false
        routeError = null
        following = true
        resetNavState()
        clearTrip()
    }

    private fun resetNavState() {
        offRoute = false
        rerouting = false
        rerouteFailed = false
        rerouteNoRoute = false
        arrived = false
        resuming = false
        offRouteFixes = 0
        wrongWayFixes = 0
        onRouteFixes = 0
        retryDelayMs = FIRST_RETRY_MS
        nextRetryAt = 0L
        lastTrackedAt = null
    }

    /** True while navigating without a usable route (off route, or resuming a trip). */
    private val needsRoute: Boolean
        get() = mode == Mode.NAVIGATE && !arrived && (route == null || offRoute)

    /** Connectivity came back: retry immediately instead of waiting for the timer. */
    fun onNetworkAvailable() {
        nextRetryAt = 0L
        location?.let { if (needsRoute) requestRoute(it) }
    }

    /**
     * Fetch a new route from [from] to the destination while navigating.
     * Used for reroutes (H3) and for resuming a restored trip (M7). One request
     * at a time; failures back off 5 s → 10 s → 20 s (cap) and retry.
     */
    private fun requestRoute(from: LatLon) {
        val dest = destination ?: return
        // Never two requests at once (a cancelled one is no longer active).
        if (navJob?.isActive == true || clock() < nextRetryAt) return
        val id = ++routeRequestId
        rerouting = true
        val heading = reliableBearing?.takeIf { clock() - reliableBearingAt <= BEARING_MAX_AGE_MS }
        navJob = scope.launch {
            try {
                // Hard ceiling, whatever the servers do: the driver must not be
                // left on "Finding a new route…" indefinitely (M4).
                val r = withTimeoutOrNull(REROUTE_DEADLINE_MS) { rerouter(from, dest.location, heading) }
                    ?: throw RoutingUnavailableException("Reroute timed out")
                if (mode != Mode.NAVIGATE || arrived) return@launch
                // Use the newest position: the car kept moving while we waited.
                installRoute(r, location ?: from)
                offRoute = false
                rerouteFailed = false
                rerouteNoRoute = false
                resuming = false
                offRouteFixes = 0
                wrongWayFixes = 0
                onRouteFixes = 0
                retryDelayMs = FIRST_RETRY_MS
                nextRetryAt = 0L
            } catch (e: CancellationException) {
                throw e
            } catch (e: NoRouteException) {
                // A real answer, not a network problem: retrying at once won't
                // help, but the car is moving, so try again a little later.
                rerouteFailed = true
                rerouteNoRoute = true
                nextRetryAt = clock() + NO_ROUTE_RETRY_MS
            } catch (e: Exception) {
                rerouteFailed = true
                rerouteNoRoute = false
                nextRetryAt = clock() + retryDelayMs
                retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_MS)
            } finally {
                // A cancelled older request must not clear the flag of a newer one.
                if (id == routeRequestId) rerouting = false
            }
        }
    }

    // ---- Location -----------------------------------------------------------

    /** Every position report from Android comes through here. */
    fun onFix(f: Fix): FixVerdict {
        val verdict = fixFilter.check(f, navigating = mode == Mode.NAVIGATE)
        if (verdict != FixVerdict.ACCEPT) return verdict

        val p = f.position
        val prev = lastFix
        location = p
        lastFix = p
        lastFixAccuracy = f.accuracy
        speedKmh = f.speedMps?.let { (it * 3.6f).roundToInt() }
        // Only trust heading when actually moving; otherwise the arrow spins.
        if (f.bearing != null && (f.speedMps ?: 0f) > 1.5f) heading = f.bearing
        else if (prev != null && Geo.distance(prev, p) > 8) heading = Geo.bearing(prev, p).toFloat()
        // Matching and rerouting only trust the GPS course when clearly moving.
        val trusted = f.bearing?.takeIf { (f.speedMps ?: 0f) >= TRUST_BEARING_MPS }
        if (trusted != null) { reliableBearing = trusted; reliableBearingAt = clock() }

        if (mode == Mode.PREVIEW && route == null && !busy && routeError == null) computeRoute()
        if (mode == Mode.NAVIGATE) navigate(p, f.timeMs, trusted)
        return verdict
    }

    private fun navigate(p: LatLon, timeMs: Long, bearing: Float?) {
        if (arrived) return
        val t = tracker
        if (t == null || route == null) { // resuming: no route yet
            requestRoute(p)
            return
        }
        val dt = lastTrackedAt?.let { (timeMs - it) / 1000.0 }
        lastTrackedAt = timeMs
        val pr = t.update(p, dt, bearing)
        progress = pr

        if (pr.arrived && !offRoute) {
            arrived = true // latched: announced once
            navJob?.cancel()
            rerouting = false
            clearTrip()
            return
        }

        // Off-route needs 3 consecutive good fixes beyond the limit (a single
        // bad fix never counts); between 25 m and the limit the count holds.
        // Driving against the route's direction (e.g. the other carriageway of
        // a divided road, or turned around) counts as off route too (M5).
        val limit = max(OFF_ROUTE_M, 1.5 * (lastFixAccuracy ?: 0f))
        when {
            pr.offRoute > limit -> offRouteFixes++
            pr.offRoute < BACK_ON_ROUTE_M -> offRouteFixes = 0
        }
        when {
            pr.wrongWay -> wrongWayFixes++
            bearing != null -> wrongWayFixes = 0 // heading known and matches the route
        }
        val onLine = pr.offRoute < BACK_ON_ROUTE_M && !pr.wrongWay && wrongWayFixes == 0

        if (!offRoute) {
            if (offRouteFixes >= CONFIRM_FIXES || wrongWayFixes >= CONFIRM_FIXES) {
                offRoute = true
                onRouteFixes = 0
                requestRoute(p)
            }
            return
        }

        // Off route: if the driver rejoins the planned route on their own, it is
        // valid again — resume its instructions without needing the internet.
        onRouteFixes = if (onLine) onRouteFixes + 1 else 0
        if (onRouteFixes >= CONFIRM_FIXES) {
            navJob?.cancel()
            rerouting = false
            offRoute = false
            rerouteFailed = false
            rerouteNoRoute = false
            offRouteFixes = 0
            wrongWayFixes = 0
            retryDelayMs = FIRST_RETRY_MS
            nextRetryAt = 0L
            return
        }
        requestRoute(p) // no-op while a request is in flight or backing off
    }

    // ---- Trip persistence (M7) ----------------------------------------------

    private fun saveTrip() {
        val d = destination ?: return
        store.putString(
            KEY_TRIP,
            JSONObject().put("n", d.name).put("d", d.detail)
                .put("lat", d.location.lat).put("lon", d.location.lon)
                .put("at", wallClock()).toString()
        )
    }

    private fun clearTrip() = store.putString(KEY_TRIP, null)

    /**
     * Called at startup: if a trip was in progress when the app was closed,
     * go straight back into navigation. The old route is not reused — a fresh
     * one is requested from the current position once a good fix arrives.
     */
    fun restoreTrip(): Boolean {
        val raw = store.getString(KEY_TRIP) ?: return false
        val place = try {
            val o = JSONObject(raw)
            val lat = o.getDouble("lat")
            val lon = o.getDouble("lon")
            val age = wallClock() - o.getLong("at")
            require(lat in -90.0..90.0 && lon in -180.0..180.0 && !lat.isNaN() && !lon.isNaN())
            require(age in 0..TRIP_MAX_AGE_MS)
            Place(o.getString("n"), o.optString("d"), LatLon(lat, lon))
        } catch (e: Exception) {
            clearTrip() // corrupt, incomplete or too old: start fresh
            return false
        }
        destination = place
        route = null
        progress = null
        tracker = null
        resetNavState()
        resuming = true
        following = true
        mode = Mode.NAVIGATE
        location?.let { requestRoute(it) }
        return true
    }

    // ---- Recent destinations -----------------------------------------------

    private fun addRecent(p: Place) {
        val list = listOf(p) + recents.filterNot {
            it.name == p.name && Geo.distance(it.location, p.location) < 200
        }
        recents = list.take(8)
        val arr = JSONArray()
        for (r in recents) arr.put(
            JSONObject().put("n", r.name).put("d", r.detail)
                .put("lat", r.location.lat).put("lon", r.location.lon)
        )
        store.putString("recents", arr.toString())
    }

    private fun loadRecents(): List<Place> = try {
        val arr = JSONArray(store.getString("recents") ?: "[]")
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Place(o.getString("n"), o.optString("d"), LatLon(o.getDouble("lat"), o.getDouble("lon")))
        }
    } catch (e: Exception) {
        emptyList()
    }

    companion object {
        const val KEY_TRIP = "active_trip"
        const val OFF_ROUTE_M = 45.0
        const val BACK_ON_ROUTE_M = 25.0
        const val CONFIRM_FIXES = 3
        const val FIRST_RETRY_MS = 5_000L
        const val MAX_RETRY_MS = 20_000L
        const val NO_ROUTE_RETRY_MS = 30_000L
        const val REROUTE_DEADLINE_MS = 16_000L // > 2 servers × 7 s attempts
        const val TRUST_BEARING_MPS = 2.5f       // ~9 km/h; slower and GPS course is noise
        const val BEARING_MAX_AGE_MS = 5_000L
        const val TRIP_MAX_AGE_MS = 6L * 60 * 60 * 1000 // resume trips up to 6 h old
    }
}
