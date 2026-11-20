package com.moeez.carnav

/** One position report from Android, reduced to what CarNav needs. Pure Kotlin. */
data class Fix(
    val position: LatLon,
    val provider: String,      // "gps", "network", …
    val accuracy: Float?,      // metres (68% radius); null if the provider didn't say
    val ageMs: Long,           // how old the fix was when it reached us
    val timeMs: Long,          // when it was taken, on the elapsed-realtime clock
    val speedMps: Float? = null,
    val bearing: Float? = null
) {
    val isGps: Boolean get() = provider == GPS

    companion object {
        const val GPS = "gps"
    }
}

/** Why a fix was not used. */
enum class FixVerdict { ACCEPT, STALE, INACCURATE, NETWORK_WHILE_NAVIGATING, NETWORK_WHILE_GPS_OK, JUMP }

/**
 * Decides which fixes are good enough to use (audit finding M1). Rules:
 *  - older than [MAX_AGE_MS] → stale;
 *  - while navigating: GPS only, accuracy ≤ [NAV_MAX_ACCURACY_M];
 *  - while browsing: accuracy ≤ [BROWSE_MAX_ACCURACY_M], and network fixes only
 *    when GPS has been silent for [GPS_FRESH_MS];
 *  - a fix implying an impossible speed from the last good one is a glitch —
 *    unless [JUMP_CONFIRMATIONS] fixes in a row agree (GPS really did relocate,
 *    e.g. after a tunnel), so the filter can never lock the car out for good.
 */
class FixFilter {
    private var last: Fix? = null
    private var lastGpsAt = Long.MIN_VALUE / 2
    private var jumpStreak = 0
    private var jumpAnchor: LatLon? = null

    fun check(f: Fix, navigating: Boolean): FixVerdict {
        if (f.ageMs > MAX_AGE_MS) return FixVerdict.STALE
        if (!f.isGps) {
            if (navigating) return FixVerdict.NETWORK_WHILE_NAVIGATING
            if (f.timeMs - lastGpsAt < GPS_FRESH_MS) return FixVerdict.NETWORK_WHILE_GPS_OK
        }
        val acc = f.accuracy
        if (acc != null && acc > (if (navigating) NAV_MAX_ACCURACY_M else BROWSE_MAX_ACCURACY_M)) {
            return FixVerdict.INACCURATE
        }

        val prev = last
        if (prev != null && f.isGps && prev.isGps) {
            val dt = ((f.timeMs - prev.timeMs) / 1000.0).coerceAtLeast(1.0)
            val dist = Geo.distance(prev.position, f.position)
            if (dist > MIN_JUMP_M && dist / dt > MAX_SPEED_MPS) {
                // Count only jumps that agree with each other (same new area).
                val anchor = jumpAnchor
                jumpStreak = if (anchor != null && Geo.distance(anchor, f.position) < MIN_JUMP_M) jumpStreak + 1 else 1
                jumpAnchor = f.position
                if (jumpStreak < JUMP_CONFIRMATIONS) return FixVerdict.JUMP
            }
        }

        jumpStreak = 0
        jumpAnchor = null
        last = f
        if (f.isGps) lastGpsAt = f.timeMs
        return FixVerdict.ACCEPT
    }

    companion object {
        const val MAX_AGE_MS = 10_000L
        const val NAV_MAX_ACCURACY_M = 50f
        const val BROWSE_MAX_ACCURACY_M = 250f
        const val GPS_FRESH_MS = 5_000L
        const val MIN_JUMP_M = 100.0
        const val MAX_SPEED_MPS = 70.0   // ~250 km/h
        const val JUMP_CONFIRMATIONS = 3
    }
}
