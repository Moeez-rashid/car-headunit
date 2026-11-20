package com.moeez.carnav

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

/** Small geometry helpers. Pure Kotlin so they can be unit-tested on the JVM. */
object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Great-circle distance in metres. */
    fun distance(a: LatLon, b: LatLon): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) *
            sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Initial compass bearing from a to b, 0..360 degrees. */
    fun bearing(a: LatLon, b: LatLon): Double {
        val la1 = Math.toRadians(a.lat)
        val la2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(la2)
        val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /**
     * Distance in metres from p to segment a-b, and how far along the segment
     * (0..1) the closest point is. Uses a local flat projection around p, which
     * is accurate to well under a metre at the scale of road segments.
     */
    fun toSegment(p: LatLon, a: LatLon, b: LatLon): Pair<Double, Double> {
        val mPerDegLat = 111_320.0
        val mPerDegLon = 111_320.0 * cos(Math.toRadians(p.lat))
        val ax = (a.lon - p.lon) * mPerDegLon
        val ay = (a.lat - p.lat) * mPerDegLat
        val bx = (b.lon - p.lon) * mPerDegLon
        val by = (b.lat - p.lat) * mPerDegLat
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx
        val cy = ay + t * dy
        return sqrt(cx * cx + cy * cy) to t
    }

    /** "350 m", "1.2 km", "14 km" — rounded the way car navigation reads it. */
    fun formatDistance(m: Double): String = when {
        m < 50 -> "${(m / 5).roundToInt() * 5} m"
        m < 1000 -> "${(m / 50).roundToInt() * 50} m"
        m < 10_000 -> "%.1f km".format(m / 1000)
        else -> "${(m / 1000).roundToInt()} km"
    }

    /** "45 sec" is never shown — minimum "1 min"; then "18 min", "1 h 5 min". */
    fun formatDuration(seconds: Double): String {
        val mins = (seconds / 60).roundToInt().coerceAtLeast(1)
        return if (mins < 60) "$mins min" else "${mins / 60} h ${mins % 60} min"
    }
}
