package com.moeez.carnav

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope

/**
 * Holds the navigation state outside the activity, so it survives the
 * activity being recreated by a configuration change (L3). Process death is
 * covered separately by the saved trip (M7). The map view itself stays in the
 * activity: it is a View and must not outlive it.
 */
class CarNavViewModel(app: Application) : AndroidViewModel(app) {

    val prefs: KeyValueStore = run {
        val sp = app.getSharedPreferences("carnav", Context.MODE_PRIVATE)
        object : KeyValueStore {
            override fun getString(key: String) = sp.getString(key, null)
            override fun putString(key: String, value: String?) {
                if (value == null) sp.edit().remove(key).apply() else sp.edit().putString(key, value).apply()
            }
        }
    }

    val model = NavModel(
        viewModelScope, prefs,
        rerouter = { from, to, heading -> Api.route(from, to, heading, Api.RouteProfile.NAVIGATION) }
    ).also {
        // An unfinished trip from before the app was closed (M7). Only on a
        // fresh start — never on recreation, which would throw away the route.
        it.restoreTrip()
    }

    val musicHub = MusicHub(app)
}
