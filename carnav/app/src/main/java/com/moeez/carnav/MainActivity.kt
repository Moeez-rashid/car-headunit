package com.moeez.carnav

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider

class MainActivity : ComponentActivity() {

    private lateinit var vm: CarNavViewModel
    private val model get() = vm.model
    private val prefs get() = vm.prefs
    private lateinit var binder: MapBinder
    private val locationManager by lazy { getSystemService(LOCATION_SERVICE) as LocationManager }
    private var subscribed = false
    private var providerReceiverRegistered = false
    private var permissionDialogOpen = false

    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            permissionDialogOpen = false
            refreshPermission()
        }

    /**
     * A full LocationListener object, not a lambda: on Android 10 the
     * onProviderEnabled/Disabled/onStatusChanged methods are abstract (they only
     * became default methods in Android 11), so a lambda listener would crash
     * with AbstractMethodError as soon as GPS is switched on or off.
     */
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onAndroidFix(location)
        override fun onProviderEnabled(provider: String) = refreshGpsState()
        override fun onProviderDisabled(provider: String) = refreshGpsState()
        @Deprecated("Required on API < 29; unused")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    /** Location switched on/off in Settings or the quick panel (H2). */
    private val providersChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshGpsState()
            if (!subscribed) subscribe()
        }
    }

    /** Internet came back: retry a pending reroute immediately (H3). */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread { model.onNetworkAvailable() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Survives recreation; a new map view is made for each activity (L3).
        vm = ViewModelProvider(this)[CarNavViewModel::class.java]
        binder = MapBinder(this)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (model.mode) {
                    Mode.SEARCH -> model.closeSearch()
                    Mode.PREVIEW -> model.end()
                    // Don't end a drive by accident: just send CarNav to the background.
                    Mode.NAVIGATE -> if (model.arrived) model.end() else moveTaskToBack(true)
                    Mode.BROWSE -> finish()
                }
            }
        })

        setContent {
            CarNavApp(
                model, binder,
                onRequestPermission = { requestPermission() },
                onOpenAppSettings = { openAppSettings() },
                hub = vm.musicHub
            )
        }

        runCatching {
            (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager)
                .registerDefaultNetworkCallback(networkCallback)
        }

        // First launch: ask straight away. Afterwards the UI offers the button.
        if (savedInstanceState == null && !hasLocationPermission() && prefs.getString(KEY_ASKED) == null) {
            requestPermission()
        }
    }

    override fun onStart() {
        super.onStart()
        // Also catches permission granted in Settings while we were away (M2).
        refreshPermission()
        registerReceiver(providersChanged, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION))
        providerReceiverRegistered = true
        refreshGpsState()
    }

    override fun onResume() {
        super.onResume()
        binder.onResume()
    }

    override fun onPause() {
        super.onPause()
        binder.onPause()
    }

    override fun onStop() {
        super.onStop()
        if (providerReceiverRegistered) {
            unregisterReceiver(providersChanged)
            providerReceiverRegistered = false
        }
        // No need for GPS in the background unless a trip is running.
        if (model.mode != Mode.NAVIGATE || model.arrived) unsubscribe()
    }

    override fun onDestroy() {
        super.onDestroy()
        unsubscribe()
        binder.onDestroy()
        runCatching {
            (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager)
                .unregisterNetworkCallback(networkCallback)
        }
    }

    // ---- Permission (M2) ------------------------------------------------------

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestPermission() {
        prefs.putString(KEY_ASKED, "1")
        permissionDialogOpen = true
        model.permission = LocationPermission.NEEDED
        permissionRequest.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    private fun refreshPermission() {
        if (hasLocationPermission()) {
            model.permission = LocationPermission.GRANTED
            subscribe()
            return
        }
        unsubscribe()
        if (permissionDialogOpen) return // the system dialog is still showing
        val asked = prefs.getString(KEY_ASKED) != null
        // After a denial, Android only shows the rationale flag while it is still
        // willing to show the dialog. False after asking = "Don't ask again".
        val canAsk = !asked || shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
        model.permission = if (canAsk) LocationPermission.NEEDED else LocationPermission.BLOCKED
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    // ---- Location subscription (H2) -----------------------------------------

    /**
     * Subscribes once to GPS (and network for a rough first position) whether or
     * not they are switched on right now: Android starts delivering fixes as
     * soon as the provider is enabled, so turning location on later just works.
     */
    @SuppressLint("MissingPermission") // guarded by hasLocationPermission()
    private fun subscribe() {
        if (subscribed || !hasLocationPermission()) return
        val all = locationManager.allProviders
        var any = false
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (p !in all) continue
            try {
                locationManager.requestLocationUpdates(p, 1000L, 0f, listener, Looper.getMainLooper())
                any = true
            } catch (e: Exception) {
                // SecurityException / IllegalArgumentException: skip this provider.
            }
        }
        subscribed = any
        // A recent last-known fix gives a position straight away (the filter
        // rejects it if it's stale). Never while navigating: a resumed trip
        // must be routed from a live fix, not from where the car used to be.
        if (any && model.mode != Mode.NAVIGATE) {
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                runCatching { locationManager.getLastKnownLocation(p) }.getOrNull()?.let { onAndroidFix(it) }
            }
        }
        refreshGpsState()
    }

    private fun unsubscribe() {
        if (!subscribed) return
        runCatching { locationManager.removeUpdates(listener) }
        subscribed = false
    }

    private fun refreshGpsState() {
        model.gpsEnabled = runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }
            .getOrDefault(false)
    }

    private fun onAndroidFix(loc: Location) {
        val ageMs = (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000
        model.onFix(
            Fix(
                position = LatLon(loc.latitude, loc.longitude),
                provider = loc.provider ?: "",
                accuracy = if (loc.hasAccuracy()) loc.accuracy else null,
                ageMs = ageMs,
                timeMs = loc.elapsedRealtimeNanos / 1_000_000,
                speedMps = if (loc.hasSpeed()) loc.speed else null,
                bearing = if (loc.hasBearing()) loc.bearing else null
            )
        )
    }

    companion object {
        private const val KEY_ASKED = "asked_location"
    }
}
