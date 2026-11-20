package com.moeez.cardash

import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.ComponentName
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

// Navigation app launched by the Navigation button. MapFactor Navigator is
// used because it supports OpenGL ES 2.0 (Organic Maps needs ES 3.0, which this
// unit's GPU lacks). Confirm on the unit with:
//   adb shell pm list packages | findstr /I "mapfactor navigator"
private const val NAV_APP_PACKAGE = "com.mapfactor.navigator"
// CarNav (our own lightweight navigation app) is preferred when installed;
// MapFactor stays as the fallback.
private val NAV_APP_PACKAGES = listOf("com.moeez.carnav", NAV_APP_PACKAGE)

// The head unit's Bluetooth (pairing/connection) app. Confirmed as the first
// entry; the rest are fallbacks for other ROMs.
private val BLUETOOTH_APP_PACKAGES = listOf(
    "com.autochips.bluetooth",
    "com.autochips.bt",
    "com.hct.bt",
    "com.android.bluetooth",
    "com.mtk.bt"
)

// The head unit's Bluetooth MUSIC player. On AutoChips units this is usually a
// separate ACTIVITY inside com.autochips.bluetooth rather than its own package.
// Fill these from the unit with the BT Music app open, via:
//   adb shell dumpsys window windows | findstr /I "mCurrentFocus mFocusedApp"
// Put the package in CAR_MUSIC_PACKAGE and the activity in CAR_MUSIC_ACTIVITY.
private const val CAR_MUSIC_PACKAGE = "com.autochips.bluetooth"
private const val CAR_MUSIC_ACTIVITY = "com.autochips.bluetooth.BtMusicActivity"
// Or, if the BT-music player is its own standalone app, its package here:
private val CAR_MUSIC_PACKAGES = listOf<String>()

// ---- Auto-start music -------------------------------------------------------
// The AutoChips BT stack only starts routing phone audio (and fetching track
// titles) after BtMusicActivity has been opened once per connection. CarDash
// does that automatically: it opens BtMusicActivity, waits for the phone to
// connect, keeps it up for KICK_HOLD_MS so titles load, then returns to
// CarDash's MUSIC screen. It never presses play — that's left to the driver.
private const val AUTO_START_MUSIC = true
// At car power-on, start the sequence this soon even if no phone has connected
// yet (opening the BT app also makes the unit auto-reconnect the phone).
private const val BOOT_KICK_DELAY_MS = 3_000L
// While the BT music screen is up, how long to wait for a phone to connect
// before giving up and returning to CarDash's home screen.
private const val WAIT_FOR_PHONE_MS = 15_000L
// Once connected, how long BtMusicActivity stays up so titles can load.
// (The music page now refreshes titles live, so this can stay short.)
private const val KICK_HOLD_MS = 3_000L
// If you leave the car's music screen yourself (e.g. to the Bluetooth app to
// connect a friend's phone), CarDash cancels the auto-return. Detecting that
// needs "usage access", granted once from the laptop with:
//   adb shell appops set com.moeez.cardash GET_USAGE_STATS allow
private const val TAG = "CarDash"

/** Process-wide so activity recreation doesn't re-trigger a kick. */
private object AutoStart {
    val processStart: Long = SystemClock.elapsedRealtime()
    var kickedThisConnection = false
    var bootKickDone = false
    var kickInProgress = false
}

// Futuristic display font for the clock (bundled in res/font, OFL-licensed).
private val Orbitron = FontFamily(
    Font(R.font.orbitron, FontWeight.Medium),
    Font(R.font.orbitron, FontWeight.Bold)
)

// ---- Design tokens (from the handoff) ---------------------------------------
private val Bg = Color(0xFF000000)
private val Fg = Color(0xFFFFFFFF)
private val BtnBg = Color.White.copy(alpha = 0.05f)     // outline variant
private val BtnBorder = Color.White.copy(alpha = 0.15f)
private val Muted70 = Color.White.copy(alpha = 0.70f)
private val Muted60 = Color.White.copy(alpha = 0.60f)
private val Muted50 = Color.White.copy(alpha = 0.50f)
private val Muted45 = Color.White.copy(alpha = 0.45f)
private val Muted40 = Color.White.copy(alpha = 0.40f)
private val Muted35 = Color.White.copy(alpha = 0.35f)
private val Muted15 = Color.White.copy(alpha = 0.15f)

// Sizes in the handoff are authored in vw against a 1920px-wide reference.
// We reproduce that responsively: 1vw == (screenWidth / 100).
private fun vwDp(width: Dp, x: Float): Dp = (width.value * x / 100f).dp
private fun vwSp(width: Dp, x: Float): TextUnit = (width.value * x / 100f).sp

class MainActivity : ComponentActivity() {

    private lateinit var mediaSessionManager: MediaSessionManager
    private var activeController by mutableStateOf<MediaController?>(null)
    private var notificationAccess by mutableStateOf(false)
    private var boostStrength by mutableStateOf(0f)
    private var boostSupported by mutableStateOf(true)
    // Which CarDash screen is showing; hoisted here so auto-start can land on "music".
    private var screen by mutableStateOf("main")

    private val mainHandler = Handler(Looper.getMainLooper())

    // Checks every 2s (while CarDash is on screen) whether a kick-start is due.
    private val autoStartTick = object : Runnable {
        override fun run() {
            checkAutoStart()
            mainHandler.postDelayed(this, 2_000L)
        }
    }

    // A single, reusable listener instance so it can be registered once and
    // removed cleanly (registering a new lambda every onResume leaks listeners).
    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            setController(pickController(controllers.orEmpty()))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaSessionManager =
            getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager

        AudioBooster.restoreOnce(applicationContext)
        syncBoost()

        setContent {
            CarDashApp(
                screen = screen,
                onScreenChange = { screen = it },
                controller = activeController,
                hasNotificationAccess = notificationAccess,
                isBluetoothAudioConnected = { isBluetoothAudioConnected() },
                onOpenNav = {
                    NAV_APP_PACKAGES.firstOrNull { packageManager.getLaunchIntentForPackage(it) != null }
                        ?.let { launchPackage(it) }
                },
                onOpenBluetoothSettings = { openBluetoothSettings() },
                onOpenCarMusic = { openCarMusic() },
                onOpenSystemHome = { openOtherLauncher() },
                onRequestNotificationAccess = { openNotificationAccessSettings() },
                boostStrength = boostStrength,
                boostSupported = boostSupported,
                onBoostChange = { v ->
                    AudioBooster.setStrength(applicationContext, v)
                    syncBoost()
                }
            )
        }
    }

    private fun syncBoost() {
        boostStrength = AudioBooster.strength
        boostSupported = AudioBooster.mode != "unsupported"
    }

    override fun onResume() {
        super.onResume()
        AudioBooster.ensure(applicationContext)
        syncBoost()
        mainHandler.removeCallbacks(autoStartTick)
        mainHandler.post(autoStartTick)
        notificationAccess = hasNotificationAccess()
        refreshActiveSession()
        val listenerComponent = ComponentName(this, NotificationListener::class.java)
        try {
            mediaSessionManager.addOnActiveSessionsChangedListener(
                sessionsListener, listenerComponent
            )
        } catch (e: SecurityException) {
            // Notification access not granted yet - the Music screen surfaces a
            // button to grant it. Nothing to do here.
        }
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(autoStartTick)
        try {
            mediaSessionManager.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (e: Exception) { /* was never registered */ }
    }

    private fun refreshActiveSession() {
        val listenerComponent = ComponentName(this, NotificationListener::class.java)
        val sessions = try {
            mediaSessionManager.getActiveSessions(listenerComponent)
        } catch (e: SecurityException) {
            emptyList()
        }
        setController(pickController(sessions))
    }

    /**
     * The unit can expose several media sessions (some idle). Prefer the one
     * actually playing, then one that has a track title, then AutoChips' own.
     */
    private fun pickController(list: List<MediaController>): MediaController? =
        list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull {
                !it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank()
            }
            ?: list.firstOrNull { it.packageName.startsWith("com.autochips") }
            ?: list.firstOrNull()

    /** Only swap controllers when the session really changed (avoids UI churn). */
    private fun setController(c: MediaController?) {
        if (c?.sessionToken != activeController?.sessionToken) activeController = c
    }

    /** Launches an installed app by package; no-op (no crash) if not installed. */
    private fun launchPackage(packageName: String) {
        packageManager.getLaunchIntentForPackage(packageName)?.let { startActivity(it) }
    }

    /**
     * True when a phone is connected over Bluetooth — independent of whether
     * music is playing. This head unit is an A2DP *sink*, so the standard
     * getProfileConnectionState(A2DP) (the *source* role) reports disconnected
     * even while audio streams. We therefore check several profiles including
     * the hidden A2DP_SINK, then fall back to a role-agnostic per-device check.
     */
    private fun isBluetoothAudioConnected(): Boolean {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return false
        if (!adapter.isEnabled) return false

        // 1) Try known profiles: A2DP(2), HEADSET(1), and hidden A2DP_SINK(11).
        for (profile in intArrayOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET, 11)) {
            try {
                if (adapter.getProfileConnectionState(profile) ==
                    BluetoothProfile.STATE_CONNECTED
                ) return true
            } catch (e: Exception) {
                // Profile not supported on this ROM — try the next.
            }
        }

        // 2) Fallback: is any bonded device actually connected right now?
        //    device.isConnected() is hidden but stable across these ROMs.
        try {
            for (device in adapter.bondedDevices ?: emptySet()) {
                val connected = device.javaClass.getMethod("isConnected").invoke(device)
                if (connected == true) return true
            }
        } catch (e: Exception) {
            // Reflection blocked — nothing more we can do.
        }
        return false
    }

    /**
     * Opens the head unit's Bluetooth connection screen. These AC8227L units
     * use a custom Bluetooth app rather than stock Android settings, so we try
     * the unit's own app first, then standard intents, firing only what the
     * device can actually resolve. Update BLUETOOTH_APP_PACKAGES below once the
     * real package is confirmed (see how-to in the chat).
     */
    private fun openBluetoothSettings() {
        val candidates: List<Intent> = buildList {
            // 1) The head unit's own Bluetooth app, by package.
            for (pkg in BLUETOOTH_APP_PACKAGES) {
                packageManager.getLaunchIntentForPackage(pkg)?.let { add(it) }
            }
            // 2) Standard Android Bluetooth settings screens.
            add(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            add(Intent("android.settings.BLUETOOTH_SETTINGS"))
            // 3) Last resort: the general Settings app.
            add(Intent(Settings.ACTION_SETTINGS))
        }
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(packageManager) != null) {
                try {
                    startActivity(intent)
                    return
                } catch (e: Exception) {
                    // Try the next candidate.
                }
            }
        }
    }

    /** Opens the head unit's Bluetooth MUSIC player to kick-start the audio route. */
    private fun openCarMusic() {
        // 1) Explicit BT-music activity, if we know it.
        if (CAR_MUSIC_PACKAGE.isNotEmpty() && CAR_MUSIC_ACTIVITY.isNotEmpty()) {
            try {
                startActivity(
                    Intent(Intent.ACTION_MAIN)
                        .setClassName(CAR_MUSIC_PACKAGE, CAR_MUSIC_ACTIVITY)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return
            } catch (e: Exception) {
                // Fall through.
            }
        }
        // 2) Standalone BT-music app, if any.
        for (pkg in CAR_MUSIC_PACKAGES) {
            packageManager.getLaunchIntentForPackage(pkg)?.let {
                startActivity(it)
                return
            }
        }
        // 3) Fallback: the Bluetooth app (its music screen may be the default).
        openBluetoothSettings()
    }

    /**
     * Launches another Home/launcher app (the stock one) without changing the
     * default — useful when CarDash is set as Home. Falls back to the Home
     * settings screen so the user can switch launchers manually.
     */
    private fun openOtherLauncher() {
        val homeQuery = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val other = packageManager.queryIntentActivities(homeQuery, 0)
            .firstOrNull { it.activityInfo.packageName != packageName }
        if (other != null) {
            try {
                startActivity(
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .setClassName(other.activityInfo.packageName, other.activityInfo.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return
            } catch (e: Exception) {
                // Fall through to settings.
            }
        }
        try {
            startActivity(
                Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    /**
     * Runs every 2s while CarDash is on screen. Starts the kick sequence once
     * at car power-on, and again whenever a phone (re)connects.
     */
    private fun checkAutoStart() {
        refreshActiveSession() // keeps track titles fresh on the music screen
        if (!AUTO_START_MUSIC || AutoStart.kickInProgress) return

        val connected = isBluetoothAudioConnected()
        if (!connected) AutoStart.kickedThisConnection = false

        val sinceStart = SystemClock.elapsedRealtime() - AutoStart.processStart
        val bootDue = !AutoStart.bootKickDone && sinceStart > BOOT_KICK_DELAY_MS
        val connectDue = connected && !AutoStart.kickedThisConnection
        if (bootDue || connectDue) {
            AutoStart.bootKickDone = true
            kickStart(if (connected) "phone connected" else "car started, waiting for phone")
        }
    }

    /**
     * Opens BtMusicActivity, waits (up to WAIT_FOR_PHONE_MS) for the phone to
     * connect, holds KICK_HOLD_MS more so the unit loads track info, then
     * returns to CarDash on the music screen. No auto-play.
     */
    private fun kickStart(reason: String) {
        AutoStart.kickInProgress = true
        Log.i(TAG, "Auto-start: $reason -> opening BT music")
        openCarMusic()

        val opened = SystemClock.elapsedRealtime()
        var connectedAt = -1L
        mainHandler.post(object : Runnable {
            override fun run() {
                val now = SystemClock.elapsedRealtime()
                // Give the music screen a moment to come up before checking.
                if (now - opened > 1_500L && userLeftCarMusic()) {
                    Log.i(TAG, "Auto-start: user left the car music screen, cancelling return")
                    // Don't re-trigger for this connection; the manual link still works.
                    AutoStart.kickedThisConnection = isBluetoothAudioConnected()
                    AutoStart.kickInProgress = false
                    return
                }
                if (connectedAt < 0 && isBluetoothAudioConnected()) {
                    connectedAt = now
                    Log.i(TAG, "Auto-start: phone connected, holding ${KICK_HOLD_MS}ms")
                }
                when {
                    connectedAt >= 0 && now - connectedAt >= KICK_HOLD_MS -> finishKick(true)
                    connectedAt < 0 && now - opened >= WAIT_FOR_PHONE_MS -> finishKick(false)
                    else -> mainHandler.postDelayed(this, 500L)
                }
            }
        })
    }

    /**
     * True when something other than the car's music screen (or CarDash) is in
     * front — i.e. the driver navigated away themselves. Returns false when
     * usage access hasn't been granted, so behaviour falls back to the timer.
     */
    private fun userLeftCarMusic(): Boolean {
        val fg = foregroundActivity() ?: return false
        return fg.first != packageName && fg.second != CAR_MUSIC_ACTIVITY
    }

    /** (package, activity class) of the last activity to come to the front. */
    private fun foregroundActivity(): Pair<String, String>? {
        val usm = getSystemService(USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val now = System.currentTimeMillis()
        val events = try {
            usm.queryEvents(now - 60_000L, now)
        } catch (e: Exception) {
            return null
        }
        var last: Pair<String, String>? = null
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND && e.className != null) {
                last = e.packageName to e.className
            }
        }
        return last // null if no permission (queryEvents returns nothing)
    }

    private fun finishKick(connected: Boolean) {
        if (connected) {
            AutoStart.kickedThisConnection = true
            screen = "music"
        }
        Log.i(TAG, "Auto-start: returning to CarDash (${if (connected) "music" else "home"})")
        try {
            // singleTask: brings this same instance back to the front.
            applicationContext.startActivity(
                Intent(applicationContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Auto-start: couldn't return to CarDash", e)
        }
        mainHandler.postDelayed({
            refreshActiveSession()
            AutoStart.kickInProgress = false
        }, 1_000L)
    }

    private fun openNotificationAccessSettings() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun hasNotificationAccess(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners"
        )
        return enabled?.contains(packageName) == true
    }
}

// ---- App shell / navigation -------------------------------------------------

@Composable
fun CarDashApp(
    screen: String,
    onScreenChange: (String) -> Unit,
    controller: MediaController?,
    hasNotificationAccess: Boolean,
    isBluetoothAudioConnected: () -> Boolean,
    onOpenNav: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onOpenCarMusic: () -> Unit,
    onOpenSystemHome: () -> Unit,
    onRequestNotificationAccess: () -> Unit,
    boostStrength: Float,
    boostSupported: Boolean,
    onBoostChange: (Float) -> Unit
) {
    // Shared ticking clock (updates every second).
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(1000)
        }
    }

    Surface(color = Bg, modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val w = maxWidth
            when (screen) {
                "music" -> MusicScreen(
                    width = w,
                    now = now,
                    controller = controller,
                    isBluetoothAudioConnected = isBluetoothAudioConnected,
                    hasNotificationAccess = hasNotificationAccess,
                    onBack = { onScreenChange("main") },
                    onConnectBluetooth = onOpenBluetoothSettings,
                    onOpenCarMusic = onOpenCarMusic,
                    onRequestNotificationAccess = onRequestNotificationAccess,
                    boostStrength = boostStrength,
                    boostSupported = boostSupported,
                    onBoostChange = onBoostChange
                )
                else -> MainScreen(
                    width = w,
                    now = now,
                    onOpenNav = onOpenNav,
                    onOpenMusic = { onScreenChange("music") },
                    onOpenSystemHome = onOpenSystemHome
                )
            }
        }
    }
}

// ---- Main screen ------------------------------------------------------------

@Composable
private fun MainScreen(
    width: Dp,
    now: Date,
    onOpenNav: () -> Unit,
    onOpenMusic: () -> Unit,
    onOpenSystemHome: () -> Unit
) {
  Box(modifier = Modifier.fillMaxSize()) {
    // Discreet exit to the stock launcher (CarDash is set as Home).
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(vwDp(width, 1.5f))
            .clip(RoundedCornerShape(6.dp))
            .clickable { onOpenSystemHome() }
            .padding(horizontal = vwDp(width, 1f), vertical = vwDp(width, 0.6f))
    ) {
        Text(
            "SYSTEM",
            color = Muted45,
            fontSize = vwSp(width, 1f),
            letterSpacing = vwSp(width, 0.1f)
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Clock + date, vertically centered in the upper area.
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = clockString(now),
                color = Fg,
                fontFamily = Orbitron,
                fontWeight = FontWeight.Bold,
                fontSize = vwSp(width, 13f),
                letterSpacing = vwSp(width, 0.15f)
            )
            Spacer(Modifier.height(vwDp(width, 2.2f)))
            Text(
                text = dateString(now),
                color = Muted45,
                fontWeight = FontWeight.Normal,
                fontSize = vwSp(width, 1.56f),
                letterSpacing = vwSp(width, 0.16f)
            )
        }

        // Bottom row: Navigation / Lights / Music.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = vwDp(width, 5.73f)),
            horizontalArrangement = Arrangement.Center
        ) {
            LabeledCircleButton(width, "NAVIGATION", onOpenNav) {
                NavigationIcon(size = vwDp(width, 2.5f))
            }
            Spacer(Modifier.width(vwDp(width, 4.17f)))
            LabeledCircleButton(width, "MUSIC", onOpenMusic) {
                MusicIcon(size = vwDp(width, 2.29f))
            }
        }
    }
  }
}

@Composable
private fun LabeledCircleButton(
    width: Dp,
    label: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircleButton(diameter = vwDp(width, 6.77f), onClick = onClick) { icon() }
        Spacer(Modifier.height(vwDp(width, 1f)))
        Text(
            text = label,
            color = Muted70,
            fontSize = vwSp(width, 1.15f),
            letterSpacing = vwSp(width, 0.1f)
        )
    }
}

// ---- Music screen -----------------------------------------------------------

@Composable
private fun MusicScreen(
    width: Dp,
    now: Date,
    controller: MediaController?,
    isBluetoothAudioConnected: () -> Boolean,
    hasNotificationAccess: Boolean,
    onBack: () -> Unit,
    onConnectBluetooth: () -> Unit,
    onOpenCarMusic: () -> Unit,
    onRequestNotificationAccess: () -> Unit,
    boostStrength: Float,
    boostSupported: Boolean,
    onBoostChange: (Float) -> Unit
) {
    // "Connected" = a phone is connected for Bluetooth audio, regardless of
    // whether music is playing yet. Poll so the screen flips as soon as the
    // phone pairs/unpairs. (The head unit keeps an idle media session alive
    // even when nothing is connected, so we can't rely on the controller.)
    var connected by remember { mutableStateOf(isBluetoothAudioConnected()) }
    LaunchedEffect(Unit) {
        while (true) {
            connected = isBluetoothAudioConnected()
            delay(1000)
        }
    }

  Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Top row: back / centered time-date / bluetooth status.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    top = vwDp(width, 2.4f),
                    start = vwDp(width, 2.6f),
                    end = vwDp(width, 2.6f)
                )
        ) {
            // Back chevron (top-left).
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onBack() }
                    .padding(vwDp(width, 0.3f))
            ) {
                ChevronLeftIcon(size = vwDp(width, 2.08f))
            }

            // Time + date (absolutely centered).
            Column(
                modifier = Modifier.align(Alignment.TopCenter),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = clockString(now),
                    color = Muted60,
                    fontFamily = Orbitron,
                    fontWeight = FontWeight.Medium,
                    fontSize = vwSp(width, 1.1f),
                    letterSpacing = vwSp(width, 0.05f)
                )
                Text(
                    text = dateString(now),
                    color = Muted35,
                    fontSize = vwSp(width, 0.8f),
                    letterSpacing = vwSp(width, 0.1f)
                )
            }

            // Bluetooth status (top-right) — opacity reflects connection;
            // tapping opens the head unit's Bluetooth (pairing) app.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(vwDp(width, 5f))
                    .clip(CircleShape)
                    .background(BtnBg)
                    .border(1.dp, BtnBorder, CircleShape)
                    .clickable { onConnectBluetooth() },
                contentAlignment = Alignment.Center
            ) {
                BluetoothIcon(
                    size = vwDp(width, 1.7f),
                    color = if (connected) Fg else Fg.copy(alpha = 0.35f)
                )
            }
        }

        if (connected && controller != null) {
            NowPlaying(width, controller, onOpenCarPlayer = onOpenCarMusic)
        } else if (connected) {
            // Paired for audio but the head unit hasn't published a media
            // session yet — show the player shell without track details.
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Connected", color = Fg, fontWeight = FontWeight.SemiBold,
                    fontSize = vwSp(width, 2.2f))
                Spacer(Modifier.height(vwDp(width, 1f)))
                Text("Start playback on your phone", color = Muted45,
                    fontSize = vwSp(width, 1.1f))
                Spacer(Modifier.height(vwDp(width, 1.7f)))
                OpenCarPlayerLink(width, onOpenCarPlayer = onOpenCarMusic)
            }
        } else {
            NotConnected(
                width = width,
                hasNotificationAccess = hasNotificationAccess,
                onConnectBluetooth = onConnectBluetooth,
                onRequestNotificationAccess = onRequestNotificationAccess
            )
        }
    }

    // Vertical volume-boost slider down the right edge, centred under the
    // Bluetooth icon. Bottom = off (normal sound), top = maximum.
    BoostSlider(
        width = width,
        value = boostStrength,
        supported = boostSupported,
        onChange = onBoostChange,
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .padding(end = vwDp(width, 2.6f))
    )
  }
}

@Composable
private fun ColumnScope.NotConnected(
    width: Dp,
    hasNotificationAccess: Boolean,
    onConnectBluetooth: () -> Unit,
    onRequestNotificationAccess: () -> Unit
) {
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(vwDp(width, 8f))
                .clip(CircleShape)
                .background(BtnBg)
                .border(1.dp, BtnBorder, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            BluetoothIcon(size = vwDp(width, 3f), color = Fg)
        }
        Spacer(Modifier.height(vwDp(width, 1.7f)))
        Text(
            "Bluetooth Not Connected",
            color = Fg,
            fontWeight = FontWeight.SemiBold,
            fontSize = vwSp(width, 1.9f)
        )
        Spacer(Modifier.height(vwDp(width, 1.7f)))
        Text(
            "Connect a device to play music",
            color = Muted45,
            fontSize = vwSp(width, 1.1f)
        )
        Spacer(Modifier.height(vwDp(width, 1.7f)))

        // Pill button -> opens the head unit's Bluetooth connection screen.
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(vwDp(width, 3f)))
                .background(BtnBg)
                .border(1.dp, BtnBorder, RoundedCornerShape(vwDp(width, 3f)))
                .clickable { onConnectBluetooth() }
                .padding(horizontal = vwDp(width, 2.8f), vertical = vwDp(width, 1f))
        ) {
            Text(
                "CONNECT BLUETOOTH",
                color = Fg,
                fontWeight = FontWeight.SemiBold,
                fontSize = vwSp(width, 1.15f),
                letterSpacing = vwSp(width, 0.05f)
            )
        }

        // First-run: media metadata needs Notification Access to be readable.
        if (!hasNotificationAccess) {
            Spacer(Modifier.height(vwDp(width, 1.7f)))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onRequestNotificationAccess() }
                    .padding(vwDp(width, 0.4f))
            ) {
                Text(
                    "Enable media info access (needed once)",
                    color = Muted45,
                    fontSize = vwSp(width, 0.9f)
                )
            }
        }
    }
}

/**
 * Vertical volume-boost slider. Drag (or tap) anywhere on it: bottom is off
 * (the unit's normal sound), top is maximum. Changes apply live while dragging.
 */
@Composable
private fun BoostSlider(
    width: Dp,
    value: Float,
    supported: Boolean,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val trackHeight = vwDp(width, 24f)
    val thumb = vwDp(width, 2.4f)
    var heightPx by remember { mutableStateOf(1) }
    val latestOnChange by rememberUpdatedState(onChange)

    fun valueAt(y: Float): Float {
        val v = (1f - y / heightPx).coerceIn(0f, 1f)
        return if (v < 0.03f) 0f else (v * 100f).roundToInt() / 100f // snap to off near the bottom
    }

    Column(modifier = modifier.width(vwDp(width, 5f)), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "BOOST",
            color = Muted60,
            fontWeight = FontWeight.SemiBold,
            fontSize = vwSp(width, 0.85f),
            letterSpacing = vwSp(width, 0.08f)
        )
        Spacer(Modifier.height(vwDp(width, 1f)))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .onSizeChanged { heightPx = it.height.coerceAtLeast(1) }
                .pointerInput(Unit) {
                    detectTapGestures { latestOnChange(valueAt(it.y)) }
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { latestOnChange(valueAt(it.y)) }
                    ) { change, _ ->
                        change.consume()
                        latestOnChange(valueAt(change.position.y))
                    }
                },
            contentAlignment = Alignment.BottomCenter
        ) {
            // Track with the filled portion rising from the bottom.
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(vwDp(width, 0.7f))
                    .clip(RoundedCornerShape(50))
                    .background(Muted15),
                contentAlignment = Alignment.BottomCenter
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(value)
                        .background(Fg)
                )
            }
            // Thumb.
            Box(
                modifier = Modifier
                    .offset(y = -(trackHeight - thumb) * value)
                    .size(thumb)
                    .clip(CircleShape)
                    .background(if (value > 0f) Fg else Bg)
                    .border(1.dp, if (value > 0f) Fg else BtnBorder, CircleShape)
            )
        }
        Spacer(Modifier.height(vwDp(width, 1f)))
        Text(
            when {
                !supported && value > 0f -> "N/A"
                value <= 0f -> "OFF"
                else -> "${(value * 100).roundToInt()}%"
            },
            color = if (value > 0f) Fg else Muted45,
            fontWeight = FontWeight.SemiBold,
            fontSize = vwSp(width, 0.95f)
        )
    }
}

/** Small link to the head unit's own Bluetooth player (com.autochips.bluetooth),
 *  which must be opened once per connection to kick-start the audio route. */
@Composable
private fun OpenCarPlayerLink(width: Dp, onOpenCarPlayer: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable { onOpenCarPlayer() }
            .padding(vwDp(width, 0.4f))
    ) {
        Text(
            "Music not starting? Tap to open the car player once",
            color = Muted45,
            fontSize = vwSp(width, 0.9f)
        )
    }
}

@Composable
private fun ColumnScope.NowPlaying(
    width: Dp,
    controller: MediaController,
    onOpenCarPlayer: () -> Unit
) {
    // Poll the live session every 500ms. Track info is polled too (not just
    // read once), so a title or cover that arrives late still shows up.
    var position by remember { mutableStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf<String?>(null) }
    var artist by remember { mutableStateOf("") }
    var duration by remember { mutableStateOf(0L) }
    var cover by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(controller) {
        var loggedFor: String? = null
        while (true) {
            val state = controller.playbackState
            val meta = controller.metadata
            position = state?.position ?: 0L
            playing = state?.state == PlaybackState.STATE_PLAYING
            title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
            artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
            duration = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            cover = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)

            // Diagnostic, once per track: does this unit ever receive covers?
            if (title != null && title != loggedFor) {
                loggedFor = title
                val uri = meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                    ?: meta?.getString(MediaMetadata.METADATA_KEY_ART_URI)
                Log.i(
                    "CarDash",
                    "Cover art for \"$title\": " + when {
                        cover != null -> "bitmap ${cover!!.width}x${cover!!.height}"
                        uri != null -> "uri only ($uri)"
                        else -> "none"
                    }
                )
            }
            delay(500)
        }
    }
    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val art = cover
    val coverImage = remember(art) { art?.asImageBitmap() }
    val togglePlay = {
        if (playing) controller.transportControls.pause() else controller.transportControls.play()
    }
    val barWidth = vwDp(width, 46.88f)

    // Driver's choice between the cover layout and the plain one (remembered).
    val prefs = LocalContext.current.getSharedPreferences("cardash", android.content.Context.MODE_PRIVATE)
    var showCover by remember { mutableStateOf(prefs.getBoolean("show_cover", true)) }

  Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (coverImage != null && showCover) {
            // ---- Layout A: cover centred, controls spread across the bar ----
            val shape = RoundedCornerShape(vwDp(width, 0.8f))
            Image(
                bitmap = coverImage,
                contentDescription = "Album cover",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(vwDp(width, 17f))
                    .clip(shape)
                    .border(1.dp, BtnBorder, shape)
            )
            Spacer(Modifier.height(vwDp(width, 1.4f)))
            TrackTitle(width, title, sizeVw = 2.4f)
            Spacer(Modifier.height(vwDp(width, 0.4f)))
            TrackArtist(width, artist, sizeVw = 1.2f)
            Spacer(Modifier.height(vwDp(width, 1.5f)))
            TrackProgress(width, barWidth, fraction, position, duration)
            Spacer(Modifier.height(vwDp(width, 0.6f)))
            Row(
                modifier = Modifier.width(barWidth),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                PrevButton(width) { controller.transportControls.skipToPrevious() }
                PlayPauseButton(width, playing, diameterVw = 4.8f, onClick = togglePlay)
                NextButton(width) { controller.transportControls.skipToNext() }
            }
            Spacer(Modifier.height(vwDp(width, 1f)))
        } else {
            // ---- Layout C (no cover from the phone): the original design ----
            TrackTitle(width, title, sizeVw = 2.92f)
            Spacer(Modifier.height(vwDp(width, 0.73f)))
            TrackArtist(width, artist, sizeVw = 1.35f)
            Spacer(Modifier.height(vwDp(width, 2.3f)))
            TrackProgress(width, barWidth, fraction, position, duration)
            Spacer(Modifier.height(vwDp(width, 2.3f)))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PrevButton(width) { controller.transportControls.skipToPrevious() }
                Spacer(Modifier.width(vwDp(width, 3.33f)))
                PlayPauseButton(width, playing, diameterVw = 5.42f, onClick = togglePlay)
                Spacer(Modifier.width(vwDp(width, 3.33f)))
                NextButton(width) { controller.transportControls.skipToNext() }
            }
            Spacer(Modifier.height(vwDp(width, 2f)))
        }
        OpenCarPlayerLink(width, onOpenCarPlayer)
    }

    // Cover on/off toggle, left edge (mirrors the boost slider on the right).
    // Only shown when the phone is actually sending a cover image.
    if (coverImage != null) {
        val shape = RoundedCornerShape(vwDp(width, 3f))
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = vwDp(width, 2.6f))
                .clip(shape)
                .background(if (showCover) Fg else BtnBg)
                .border(1.dp, if (showCover) Fg else BtnBorder, shape)
                .clickable {
                    showCover = !showCover
                    prefs.edit().putBoolean("show_cover", showCover).apply()
                }
                .padding(horizontal = vwDp(width, 1.4f), vertical = vwDp(width, 0.7f))
        ) {
            Text(
                "COVER",
                color = if (showCover) Bg else Muted70,
                fontWeight = FontWeight.SemiBold,
                fontSize = vwSp(width, 0.95f),
                letterSpacing = vwSp(width, 0.08f)
            )
        }
    }
  }
}

@Composable
private fun TrackTitle(width: Dp, title: String?, sizeVw: Float) {
    Text(
        text = title ?: "Waiting for track info…",
        color = Fg,
        fontWeight = FontWeight.SemiBold,
        fontSize = vwSp(width, sizeVw),
        textAlign = TextAlign.Center,
        maxLines = 1
    )
}

@Composable
private fun TrackArtist(width: Dp, artist: String, sizeVw: Float) {
    Text(
        text = artist,
        color = Muted50,
        fontSize = vwSp(width, sizeVw),
        letterSpacing = vwSp(width, 0.05f),
        maxLines = 1
    )
}

@Composable
private fun TrackProgress(width: Dp, barWidth: Dp, fraction: Float, position: Long, duration: Long) {
    Column(modifier = Modifier.width(barWidth)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(vwDp(width, 0.26f).coerceAtLeast(3.dp))
                .clip(RoundedCornerShape(vwDp(width, 0.2f)))
                .background(Muted15)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .clip(RoundedCornerShape(vwDp(width, 0.2f)))
                    .background(Fg)
            )
        }
        Spacer(Modifier.height(vwDp(width, 0.63f)))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(fmtTime(position), color = Muted40, fontSize = vwSp(width, 0.94f))
            Text(fmtTime(duration), color = Muted40, fontSize = vwSp(width, 0.94f))
        }
    }
}

@Composable
private fun PrevButton(width: Dp, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(vwDp(width, 1.04f))
    ) { PrevIcon(size = vwDp(width, 1.77f)) }
}

@Composable
private fun NextButton(width: Dp, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(vwDp(width, 1.04f))
    ) { NextIcon(size = vwDp(width, 1.77f)) }
}

@Composable
private fun PlayPauseButton(width: Dp, playing: Boolean, diameterVw: Float, onClick: () -> Unit) {
    CircleButton(diameter = vwDp(width, diameterVw), onClick = onClick) {
        if (playing) PauseIcon(size = vwDp(width, 1.88f)) else PlayIcon(size = vwDp(width, 1.88f))
    }
}

// ---- Shared button ----------------------------------------------------------

@Composable
private fun CircleButton(
    diameter: Dp,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .size(diameter)
            .clip(CircleShape)
            .background(BtnBg)
            .border(1.dp, BtnBorder, CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) { content() }
}

// ---- Icons (hand-built to match the handoff's 24x24 SVGs) -------------------

/** Maps a point from the 24x24 design viewBox onto the canvas. */
private fun DrawScope.vb(x: Float, y: Float, s: Float) = Offset(x * s, y * s)

@Composable
private fun NavigationIcon(size: Dp, color: Color = Fg) = Canvas(Modifier.size(size)) {
    val s = this.size.minDimension / 24f
    drawCircle(color, radius = 10f * s, center = vb(12f, 12f, s),
        style = Stroke(width = 1.4f * s))
    val needle = Path().apply {
        moveTo(vb(12f, 6f, s).x, vb(12f, 6f, s).y)
        lineTo(vb(15.5f, 13f, s).x, vb(15.5f, 13f, s).y)
        lineTo(vb(12f, 18f, s).x, vb(12f, 18f, s).y)
        lineTo(vb(8.5f, 13f, s).x, vb(8.5f, 13f, s).y)
        close()
    }
    drawPath(needle, color)
}

@Composable
private fun MusicIcon(size: Dp, color: Color = Fg) = Canvas(Modifier.size(size)) {
    val s = this.size.minDimension / 24f
    val sw = 1.5f * s
    val stem = Path().apply {
        moveTo(vb(9f, 18f, s).x, vb(9f, 18f, s).y)
        lineTo(vb(9f, 5f, s).x, vb(9f, 5f, s).y)
        lineTo(vb(21f, 3f, s).x, vb(21f, 3f, s).y)
        lineTo(vb(21f, 16f, s).x, vb(21f, 16f, s).y)
    }
    drawPath(stem, color, style = Stroke(width = sw, join = StrokeJoin.Round, cap = StrokeCap.Round))
    drawCircle(color, radius = 3f * s, center = vb(6f, 18f, s), style = Stroke(width = sw))
    drawCircle(color, radius = 3f * s, center = vb(18f, 16f, s), style = Stroke(width = sw))
}

@Composable
private fun BluetoothIcon(size: Dp, color: Color = Fg) = Canvas(Modifier.size(size)) {
    val s = this.size.minDimension / 24f
    val p = Path().apply {
        moveTo(vb(7f, 7f, s).x, vb(7f, 7f, s).y)
        lineTo(vb(17f, 17f, s).x, vb(17f, 17f, s).y)
        lineTo(vb(12f, 22f, s).x, vb(12f, 22f, s).y)
        lineTo(vb(12f, 2f, s).x, vb(12f, 2f, s).y)
        lineTo(vb(17f, 7f, s).x, vb(17f, 7f, s).y)
        lineTo(vb(7f, 17f, s).x, vb(7f, 17f, s).y)
    }
    drawPath(p, color, style = Stroke(width = 1.5f * s, join = StrokeJoin.Round, cap = StrokeCap.Round))
}

@Composable
private fun ChevronLeftIcon(size: Dp, color: Color = Fg) = Canvas(Modifier.size(size)) {
    val s = this.size.minDimension / 24f
    val p = Path().apply {
        moveTo(vb(15f, 4f, s).x, vb(15f, 4f, s).y)
        lineTo(vb(7f, 12f, s).x, vb(7f, 12f, s).y)
        lineTo(vb(15f, 20f, s).x, vb(15f, 20f, s).y)
    }
    drawPath(p, color, style = Stroke(width = 2f * s, join = StrokeJoin.Round, cap = StrokeCap.Round))
}

@Composable
private fun PlayIcon(size: Dp, color: Color = Fg) = Canvas(Modifier.size(size)) {
    val s = this.size.minDimension / 24f
    val p = Path().apply {
        moveTo(vb(6f, 4f, s).x, vb(6f, 4f, s).y)
        lineTo(vb(20f, 12f, s).x, vb(20f, 12f, s).y)
        lineTo(vb(6f, 20f, s).x, vb(6f, 20f, s).y)
        close()
    }
    drawPath(p, color)
}

@Composable
private fun PauseIcon(size: Dp, color: Color = Fg) = Canvas(Modifier.size(size)) {
    val s = this.size.minDimension / 24f
    drawRect(color, topLeft = vb(5f, 4f, s), size = Size(5f * s, 16f * s))
    drawRect(color, topLeft = vb(14f, 4f, s), size = Size(5f * s, 16f * s))
}

@Composable
private fun PrevIcon(size: Dp, color: Color = Fg) = Canvas(Modifier.size(size)) {
    val s = this.size.minDimension / 24f
    val tri = Path().apply {
        moveTo(vb(20f, 4f, s).x, vb(20f, 4f, s).y)
        lineTo(vb(10f, 12f, s).x, vb(10f, 12f, s).y)
        lineTo(vb(20f, 20f, s).x, vb(20f, 20f, s).y)
        close()
    }
    drawPath(tri, color)
    drawRect(color, topLeft = vb(5f, 4f, s), size = Size(3f * s, 16f * s))
}

@Composable
private fun NextIcon(size: Dp, color: Color = Fg) = Canvas(Modifier.size(size)) {
    val s = this.size.minDimension / 24f
    val tri = Path().apply {
        moveTo(vb(4f, 4f, s).x, vb(4f, 4f, s).y)
        lineTo(vb(14f, 12f, s).x, vb(14f, 12f, s).y)
        lineTo(vb(4f, 20f, s).x, vb(4f, 20f, s).y)
        close()
    }
    drawPath(tri, color)
    drawRect(color, topLeft = vb(16f, 4f, s), size = Size(3f * s, 16f * s))
}

// ---- Formatting -------------------------------------------------------------

private fun clockString(now: Date): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(now)

private fun dateString(now: Date): String =
    SimpleDateFormat("EEEE · MMMM d", Locale.getDefault()).format(now).uppercase(Locale.getDefault())

private fun fmtTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}
