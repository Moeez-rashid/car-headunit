package com.moeez.cardash

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Keeps CarDash in front when the car is switched on.
 *
 * The head unit's firmware puts its stock launcher (com.android.launcher3,
 * ".testing.LauncherExtension") in front at every car power-off and wakes up
 * on it, even though CarDash is the default Home app (seen in the system log
 * 2026-11-27: launcher3 resumed at screen-off and was still in front at
 * screen-on). So after the screen turns on — and right after CarDash's
 * process is (re)started, which the unit also does at power-off — CarDash
 * checks a few times whether that stock launcher is what's in front, and if
 * so brings itself back. Anything else in front (CarNav, radio, a call…) is
 * left alone. Needs usage access (already granted); without it, it does
 * nothing.
 */
object WakeReturn {
    private const val TAG = "CarDash"
    private const val STOCK_LAUNCHER = "com.android.launcher3"
    private val CHECKS_MS = longArrayOf(1_200L, 3_500L, 7_000L)
    private var registered = false
    private val handler = Handler(Looper.getMainLooper())

    fun register(context: Context) {
        if (registered) return
        registered = true
        val app = context.applicationContext
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = scheduleChecks(app, "screen on")
        }, IntentFilter(Intent.ACTION_SCREEN_ON))
        // The unit restarts CarDash's process at power-off, just before it
        // brings its own launcher up: check then too.
        scheduleChecks(app, "process start")
    }

    private fun scheduleChecks(app: Context, why: String) {
        handler.removeCallbacksAndMessages(null)
        for (delay in CHECKS_MS) handler.postDelayed({ check(app, why) }, delay)
    }

    private fun check(app: Context, why: String) {
        val fg = lastForegroundPackage(app)
        Log.i(TAG, "Wake check ($why): in front = $fg")
        if (fg != STOCK_LAUNCHER) return
        Log.i(TAG, "Wake: stock launcher in front after $why, bringing CarDash back")
        try {
            app.startActivity(
                Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Wake: couldn't bring CarDash back", e)
        }
    }

    /** Package of the activity most recently brought to the front (last 6 h), or null. */
    private fun lastForegroundPackage(app: Context): String? {
        val usm = app.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val now = System.currentTimeMillis()
        val events = try { usm.queryEvents(now - 6 * 3_600_000L, now + 1_000L) } catch (e: Exception) { return null }
        var last: String? = null
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) last = e.packageName
        }
        return last
    }
}
