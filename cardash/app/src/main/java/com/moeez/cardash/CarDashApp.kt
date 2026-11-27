package com.moeez.cardash

import android.app.Application

/**
 * Runs whenever CarDash's process starts, for any reason (its screen, the
 * notification listener, CarNav's HubProvider, or the firmware restarting it).
 *
 * The head unit force-stops CarDash at every car power-off, restarts its
 * process, then puts its stock launcher in front — often before CarDash's
 * screen is even created. So the things that must survive between drives
 * start here: BOOST and EQ re-applied to the car audio, and the wake check
 * that brings CarDash back in front when the car is switched on.
 */
class CarDashApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AudioBooster.restoreOnce(this)
        AudioEq.restoreOnce(this)
        WakeReturn.register(this)
    }
}
