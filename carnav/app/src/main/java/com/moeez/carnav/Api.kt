package com.moeez.carnav

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/** The routing service answered: there is no drivable route (not a network problem). */
class NoRouteException : Exception("No driving route found")

/** The routing service couldn't be reached or failed (timeout, offline, server error). */
class RoutingUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A search result. */
data class Place(val name: String, val detail: String, val location: LatLon)

/**
 * Free OpenStreetMap services, no API keys:
 *  - Search: Nominatim (rule: max 1 request/second, search on submit — not as-you-type).
 *  - Routing: OSRM. Primary is the FOSSGIS server openstreetmap.org itself uses;
 *    the OSRM project's demo server is the fallback.
 * Both ask for an identifying User-Agent and light, personal-scale use.
 */
object Api {
    const val USER_AGENT = "CarNav/0.4 (personal in-car navigation app)"

    private const val SEARCH_URL = "https://nominatim.openstreetmap.org/search"
    private val ROUTE_URLS = listOf(
        "https://routing.openstreetmap.de/routed-car/route/v1/driving/",
        "https://router.project-osrm.org/route/v1/driving/"
    )

    // Pakistan, used to keep search results (and the map) inside the country.
    val PAKISTAN_SOUTH = 23.5
    val PAKISTAN_NORTH = 37.2
    val PAKISTAN_WEST = 60.8
    val PAKISTAN_EAST = 77.9

    // Nominatim allows at most 1 request per second — every call goes through here.
    private val nominatimGate = Mutex()
    private var lastNominatimAt = 0L

    private suspend fun <T> nominatim(block: () -> T): T = nominatimGate.withLock {
        val wait = 1_100 - (SystemClock.elapsedRealtime() - lastNominatimAt)
        if (wait > 0) delay(wait)
        try { withContext(Dispatchers.IO) { block() } } finally { lastNominatimAt = SystemClock.elapsedRealtime() }
    }

    /** English name of the road at [p], or null if OSM has none (or it's Urdu). */
    suspend fun reverseRoad(p: LatLon): String? = nominatim {
        val url = "https://nominatim.openstreetmap.org/reverse?lat=${p.lat}&lon=${p.lon}" +
            "&format=jsonv2&zoom=17&accept-language=en"
        val o = JSONObject(get(url))
        val road = o.optJSONObject("address")?.optString("road").orEmpty().ifBlank { o.optString("name") }
        road.takeIf { it.isNotBlank() && !Names.isUrduOnly(it) && Names.english(it) == it }
    }

    suspend fun search(query: String, near: LatLon?): List<Place> = nominatim {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val bias = near?.let {
            // Prefer results near the car without excluding the rest of Pakistan.
            val d = 0.7
            "&viewbox=${it.lon - d},${it.lat + d},${it.lon + d},${it.lat - d}&bounded=0"
        } ?: ""
        val url = "$SEARCH_URL?q=$q&format=jsonv2&countrycodes=pk&limit=10" +
            "&accept-language=en&addressdetails=0$bias"
        parseSearch(get(url))
    }

    /**
     * How long one routing attempt may take. Preview is generous (the car is
     * usually parked); a reroute while driving has to be quick, because the
     * driver has no instructions until it comes back (audit finding M4).
     * Measured from Lahore: a full-geometry route normally returns in < 1 s.
     */
    enum class RouteProfile(val connectMs: Int, val readMs: Int, val attemptMs: Long) {
        PREVIEW(connectMs = 8_000, readMs = 12_000, attemptMs = 15_000),
        NAVIGATION(connectMs = 4_000, readMs = 6_000, attemptMs = 7_000)
    }

    /**
     * Route from [from] to [to]. The primary server is tried first; the
     * fallback is used only when the primary is unreachable or broken —
     * never after a genuine "no route" answer, which would be the same on
     * both (they use the same OpenStreetMap data).
     *
     * [heading] (degrees, only when the car's direction is reliable) makes
     * the route start in the direction the car is actually travelling, so
     * on a divided road it isn't routed as if it were on the other side.
     *
     * Throws [NoRouteException] or [RoutingUnavailableException].
     */
    suspend fun route(
        from: LatLon,
        to: LatLon,
        heading: Float? = null,
        profile: RouteProfile = RouteProfile.PREVIEW
    ): Route {
        val coords = "${from.lon},${from.lat};${to.lon},${to.lat}"
        val base = "?overview=full&geometries=geojson&steps=true"
        var lastError: Exception? = null
        for (server in ROUTE_URLS) {
            try {
                val json = if (heading != null) {
                    try {
                        fetchRoute(server + coords + base + bearingParam(heading), profile)
                    } catch (e: NoRouteException) {
                        // No road leaves from here in that direction (GPS
                        // bearing noise, car park…): ask again without it.
                        fetchRoute(server + coords + base, profile)
                    }
                } else fetchRoute(server + coords + base, profile)
                return json
            } catch (e: NoRouteException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw RoutingUnavailableException(lastError?.message ?: "No routing server reachable", lastError)
    }

    /** OSRM bearing filter for the start point only: ±60° around the heading. */
    fun bearingParam(heading: Float): String {
        val b = ((heading.roundToInt() % 360) + 360) % 360
        return "&bearings=$b,60;"
    }

    /** One server, one attempt, bounded by [RouteProfile.attemptMs]. */
    private suspend fun fetchRoute(url: String, profile: RouteProfile): Route {
        val body = withTimeoutOrNull(profile.attemptMs) {
            httpGet(url, profile.connectMs, profile.readMs, acceptErrorBody = true)
        } ?: throw RoutingUnavailableException("Routing server timed out (${URL(url).host})")
        return parseRoute(body)
    }

    private fun get(url: String): String = blockingGet(url, 10_000, 15_000, acceptErrorBody = false, onOpen = {})

    /**
     * Cancellable HTTP GET: cancelling the coroutine (time limit, trip ended)
     * closes the connection, so a stalled request can't keep a reroute
     * "in progress" after it has been abandoned.
     */
    private suspend fun httpGet(url: String, connectMs: Int, readMs: Int, acceptErrorBody: Boolean): String =
        coroutineScope {
            val conn = AtomicReference<HttpURLConnection?>(null)
            val watchdog = launch(Dispatchers.IO) {
                try { awaitCancellation() } finally { conn.get()?.disconnect() }
            }
            try {
                runInterruptible(Dispatchers.IO) {
                    blockingGet(url, connectMs, readMs, acceptErrorBody) { conn.set(it) }
                }
            } finally {
                watchdog.cancel()
            }
        }

    private fun blockingGet(
        url: String,
        connectMs: Int,
        readMs: Int,
        acceptErrorBody: Boolean,
        onOpen: (HttpURLConnection) -> Unit
    ): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        onOpen(conn)
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.connectTimeout = connectMs
        conn.readTimeout = readMs
        try {
            val code = conn.responseCode
            if (code in 200..299) return conn.inputStream.bufferedReader().use { it.readText() }
            // OSRM answers "no route" / "no road near this point" with HTTP 400
            // and a JSON body: let the parser read the code. 5xx / 429 = server trouble.
            if (acceptErrorBody && code in 400..499 && code != 429) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }
                if (!err.isNullOrBlank()) return err
            }
            throw java.io.IOException("HTTP $code from ${URL(url).host}")
        } finally {
            conn.disconnect()
        }
    }

    // ---- Parsing (pure, unit-tested) -------------------------------------

    fun parseSearch(json: String): List<Place> {
        val arr = JSONArray(json)
        val out = ArrayList<Place>(arr.length())
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val display = o.optString("display_name")
            val parts = display.split(",").map { Names.english(it.trim()) }
                .filter { it.isNotEmpty() && it != "Pakistan" }
            val name = Names.english(o.optString("name")).ifBlank { parts.firstOrNull().orEmpty() }
            if (name.isBlank()) continue
            // Detail = the next few address parts, skipping repeats of the name.
            val detail = parts.filter { it != name }.take(3).joinToString(", ")
            val loc = LatLon(o.getString("lat").toDouble(), o.getString("lon").toDouble())
            // Nominatim often returns one place several times (building, entrance,
            // car park…): drop same-named results within 300 m of one already kept.
            if (out.any { it.name == name && Geo.distance(it.location, loc) < 300 }) continue
            if (!seen.add("$name|$detail")) continue
            out += Place(name, detail, loc)
        }
        return out
    }

    fun parseRoute(json: String): Route {
        val root = JSONObject(json)
        val code = root.optString("code")
        when (code) {
            "Ok" -> {}
            // Genuine answers: there is no drivable route between the points.
            "NoRoute", "NoSegment" -> throw NoRouteException()
            else -> throw RoutingUnavailableException("Routing error: $code")
        }
        val r = root.getJSONArray("routes").getJSONObject(0)

        val coords = r.getJSONObject("geometry").getJSONArray("coordinates")
        val points = ArrayList<LatLon>(coords.length())
        for (i in 0 until coords.length()) {
            val c = coords.getJSONArray(i)
            points += LatLon(c.getDouble(1), c.getDouble(0))
        }

        val steps = ArrayList<Step>()
        val legs = r.getJSONArray("legs")
        for (l in 0 until legs.length()) {
            val arr = legs.getJSONObject(l).getJSONArray("steps")
            for (s in 0 until arr.length()) {
                val st = arr.getJSONObject(s)
                val m = st.getJSONObject("maneuver")
                val loc = m.getJSONArray("location")
                steps += Step(
                    type = m.optString("type"),
                    modifier = m.optString("modifier").ifBlank { null },
                    name = st.optString("name"),
                    ref = st.optString("ref").ifBlank { null },
                    exit = if (m.has("exit")) m.getInt("exit") else null,
                    location = LatLon(loc.getDouble(1), loc.getDouble(0)),
                    distance = st.optDouble("distance", 0.0),
                    duration = st.optDouble("duration", 0.0)
                )
            }
        }
        return Route(points, r.getDouble("distance"), r.getDouble("duration"), steps)
    }
}
