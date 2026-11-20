package com.moeez.carnav

/** One manoeuvre from the routing service (OSRM's step). */
data class Step(
    val type: String,          // depart, turn, new name, continue, merge, fork, roundabout, arrive, ...
    val modifier: String?,     // left, slight right, uturn, straight, ...
    val name: String,          // road being turned onto ("" if unnamed)
    val ref: String?,          // road number, e.g. "N-5"
    val exit: Int?,            // roundabout exit number
    val location: LatLon,      // where the manoeuvre happens
    val distance: Double,      // metres of road AFTER this manoeuvre
    val duration: Double
)

/** A driving route: the line to draw plus the manoeuvres along it. */
class Route(
    val points: List<LatLon>,
    val distance: Double,      // metres
    val duration: Double,      // seconds
    val steps: List<Step>
) {
    /** cumulative[i] = metres along the line from points[0] to points[i]. */
    val cumulative: DoubleArray = DoubleArray(points.size).also { c ->
        for (i in 1 until points.size) c[i] = c[i - 1] + Geo.distance(points[i - 1], points[i])
    }

    val length: Double get() = cumulative.lastOrNull() ?: 0.0

    /** The point `m` metres along the route line. */
    fun pointAt(m: Double): LatLon {
        if (points.size < 2 || m <= 0) return points.first()
        for (i in 1 until points.size) {
            if (cumulative[i] >= m) {
                val seg = cumulative[i] - cumulative[i - 1]
                val t = if (seg > 0) (m - cumulative[i - 1]) / seg else 0.0
                val a = points[i - 1]; val b = points[i]
                return LatLon(a.lat + t * (b.lat - a.lat), a.lon + t * (b.lon - a.lon))
            }
        }
        return points.last()
    }

    /** Metres along the line at which each step's manoeuvre happens. */
    val stepAlong: DoubleArray = DoubleArray(steps.size).also { out ->
        var from = 0
        for ((k, step) in steps.withIndex()) {
            // Manoeuvres come in order along the line, so search forward only.
            var best = from
            var bestD = Double.MAX_VALUE
            for (i in from until points.size) {
                val d = Geo.distance(points[i], step.location)
                if (d < bestD) { bestD = d; best = i }
                if (bestD < 1.0) break
            }
            out[k] = cumulative[best]
            from = best
        }
    }
}

/** Where the driver is relative to the route right now. */
data class Progress(
    val along: Double,             // metres travelled along the route
    val offRoute: Double,          // metres from the route line
    val nextStep: Int,             // index into route.steps of the upcoming manoeuvre
    val toNextStep: Double,        // metres until that manoeuvre
    val remainingDistance: Double,
    val remainingDuration: Double, // seconds
    val arrived: Boolean,
    /** On the route line but driving against its direction (reliable heading only). */
    val wrongWay: Boolean = false
)

/**
 * Matches GPS fixes to the route (audit finding M5).
 *
 * Every segment is a candidate, scored by its distance from the car plus
 * penalties that encode what a car can plausibly have done since the last fix:
 *  - progress can't run further ahead than the car could have driven (hard
 *    limit; this also stops "arriving" from the opposite carriageway, M3);
 *  - jumping back to an earlier part of the route costs extra, so on a loop,
 *    U-turn or flyover the pass the car is actually on wins;
 *  - jumping far ahead costs a little, which breaks ties on overlapping roads
 *    in favour of the nearer (earlier) pass;
 *  - when the GPS heading is reliable, segments pointing the other way cost
 *    extra, which separates the two sides of a divided road.
 * A penalty is never a ban: a real rejoin further back (after a wrong turn)
 * still wins when nothing better is close by.
 */
class RouteTracker(private val route: Route) {
    private var lastAlong: Double? = null
    /** Seconds since the last fix that matched the route line. */
    private var sinceMatchSec = 0.0
    private val segBearing = DoubleArray((route.points.size - 1).coerceAtLeast(0)) { i ->
        Geo.bearing(route.points[i], route.points[i + 1])
    }

    /**
     * @param dtSec seconds since the previous update; enables the advance limit.
     * @param bearing the car's heading in degrees, or null when unknown or
     *   unreliable (stopped, slow, no GPS course).
     */
    fun update(p: LatLon, dtSec: Double? = null, bearing: Float? = null): Progress {
        val pts = route.points
        if (pts.size < 2) return Progress(0.0, 0.0, route.steps.lastIndex, 0.0, 0.0, 0.0, true)

        val prev = lastAlong
        if (dtSec != null) sinceMatchSec += dtSec.coerceAtLeast(0.0)
        val maxAlong = if (dtSec != null && prev != null) prev + MAX_ADVANCE_M + MAX_SPEED_MPS * sinceMatchSec
        else Double.MAX_VALUE

        var bestCost = Double.MAX_VALUE
        var bestD = Double.MAX_VALUE
        var bestAlong = 0.0
        var bestDiff = 0.0
        for (i in 0 until pts.size - 1) {
            val c0 = route.cumulative[i]
            if (c0 > maxAlong) break // further than the car can have driven
            val segLen = route.cumulative[i + 1] - c0
            val (d, t) = Geo.toSegment(p, pts[i], pts[i + 1])
            val a = c0 + t * segLen
            var cost = d
            if (prev != null) {
                val back = prev - a - BACK_TOLERANCE_M
                if (back > 0) cost += BACK_PENALTY_M + BACK_PENALTY_PER_M * back
                val ahead = a - prev - AHEAD_TOLERANCE_M - CRUISE_MPS * sinceMatchSec
                if (ahead > 0) cost += AHEAD_PENALTY_PER_M * ahead
            }
            var diff = 0.0
            if (bearing != null && segLen >= MIN_SEG_FOR_HEADING_M) {
                diff = angleDiff(bearing.toDouble(), segBearing[i])
                if (diff > HEADING_TOLERANCE_DEG) {
                    cost += HEADING_PENALTY_M * (diff - HEADING_TOLERANCE_DEG) / (180 - HEADING_TOLERANCE_DEG)
                }
            }
            if (cost < bestCost) {
                bestCost = cost; bestD = d; bestAlong = a; bestDiff = diff
            }
        }

        // Clearly off the line: hold progress where the car left the route
        // instead of letting it wander to whatever part happens to be nearest.
        val along = if (prev != null && bestD > HOLD_BEYOND_M) prev else bestAlong
        if (bestD <= HOLD_BEYOND_M || prev == null) sinceMatchSec = 0.0
        lastAlong = along
        val remaining = (route.length - along).coerceAtLeast(0.0)

        // Upcoming manoeuvre = first step (after "depart") still ahead of us.
        var next = route.steps.lastIndex
        for (k in 1 until route.steps.size) {
            if (route.stepAlong[k] > along + 5) { next = k; break }
        }
        val toNext = (route.stepAlong.getOrElse(next) { route.length } - along).coerceAtLeast(0.0)

        val ratio = if (route.length > 0) remaining / route.length else 0.0
        val wrongWay = bearing != null && bestDiff > WRONG_WAY_DEG && bestD <= HOLD_BEYOND_M
        // Arrival is by distance left ALONG the route only — never just by being
        // near the destination (a divided road can put it 20 m away but a
        // U-turn ahead). Also require being on the route line itself.
        val arrived = remaining < ARRIVE_M && bestD < 50 && !wrongWay
        return Progress(along, bestD, next, toNext, remaining, route.duration * ratio, arrived, wrongWay)
    }

    companion object {
        const val ARRIVE_M = 30.0
        private const val MAX_ADVANCE_M = 80.0      // slack for GPS noise and fix timing
        private const val MAX_SPEED_MPS = 50.0      // 180 km/h
        private const val CRUISE_MPS = 15.0         // typical city speed, for the "far ahead" cost
        private const val AHEAD_TOLERANCE_M = 20.0
        private const val AHEAD_PENALTY_PER_M = 0.1
        private const val BACK_TOLERANCE_M = 25.0   // GPS jitter can put us a little behind
        private const val BACK_PENALTY_M = 40.0
        private const val BACK_PENALTY_PER_M = 0.1
        private const val HEADING_TOLERANCE_DEG = 45.0
        private const val HEADING_PENALTY_M = 80.0  // full penalty when facing the opposite way
        private const val MIN_SEG_FOR_HEADING_M = 5.0
        private const val WRONG_WAY_DEG = 135.0
        private const val HOLD_BEYOND_M = 50.0

        fun angleDiff(a: Double, b: Double): Double {
            val d = Math.abs(((a - b) % 360 + 360) % 360)
            return if (d > 180) 360 - d else d
        }
    }
}
