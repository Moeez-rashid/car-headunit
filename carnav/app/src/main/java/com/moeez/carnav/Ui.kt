package com.moeez.carnav

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private const val MESSAGE_MS = 8_000L

// Same look as CarDash: black, white, Orbitron for numbers.
private val Orbitron = FontFamily(Font(R.font.orbitron, FontWeight.Medium), Font(R.font.orbitron, FontWeight.Bold))
private val Fg = Color.White
private val Panel = Color(0xF2000000)
private val Line = Color.White.copy(alpha = 0.15f)
private val Muted = Color.White.copy(alpha = 0.55f)
private val Faint = Color.White.copy(alpha = 0.35f)

// Sizes scale with screen width so it fits whatever resolution the unit has.
private class Scale(val w: Dp) {
    fun dp(x: Float): Dp = (w.value * x / 100f).dp
    fun sp(x: Float): TextUnit = (w.value * x / 100f).sp
}

@Composable
fun CarNavApp(
    model: NavModel,
    binder: MapBinder,
    onRequestPermission: () -> Unit = {},
    onOpenAppSettings: () -> Unit = {},
    hub: MusicHub? = null
) {
    // Push model changes into the map.
    LaunchedEffect(model.route) {
        binder.showRoute(model.route)
        val r = model.route
        if (r != null && model.mode == Mode.PREVIEW) binder.fitRoute(r)
    }
    LaunchedEffect(model.destination) { binder.showDestination(model.destination?.location) }
    LaunchedEffect(model.location, model.heading, model.following, model.mode) {
        binder.showPosition(model.location, model.heading)
        val p = model.location ?: return@LaunchedEffect
        if (model.following && model.mode != Mode.PREVIEW && model.mode != Mode.SEARCH) {
            val zoom = if (model.mode == Mode.NAVIGATE) {
                val v = model.speedKmh ?: 0
                when { v < 35 -> 17.0; v < 75 -> 16.0; else -> 15.0 }
            } else null
            binder.follow(p, zoom)
        }
    }
    LaunchedEffect(Unit) { binder.onUserPan = { model.following = false } }
    // Long enough to read at a glance while driving; tap to dismiss sooner (L6).
    LaunchedEffect(model.message) {
        if (model.message != null) { delay(MESSAGE_MS); model.message = null }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val s = remember(maxWidth) { Scale(maxWidth) }
        AndroidView(factory = { binder.map }, modifier = Modifier.fillMaxSize())

        when (model.mode) {
            Mode.BROWSE -> {
                SearchPill(s, Modifier.align(Alignment.TopStart).padding(s.dp(2f))) { model.openSearch() }
                // Only while moving — a parked "0" is noise.
                model.speedKmh?.takeIf { it >= 3 }?.let { SpeedBadge(s, it, Modifier.align(Alignment.BottomStart).padding(s.dp(2f))) }
            }
            Mode.SEARCH -> SearchPanel(s, model, Modifier.align(Alignment.TopStart))
            Mode.PREVIEW -> PreviewCard(s, model, Modifier.align(Alignment.BottomStart).padding(s.dp(2f)))
            Mode.NAVIGATE -> {
                TurnBanner(s, model, Modifier.align(Alignment.TopStart).padding(s.dp(2f)))
                TripBar(s, model, Modifier.align(Alignment.BottomStart).padding(s.dp(2f)))
            }
        }

        // Right-hand map controls.
        if (model.mode != Mode.SEARCH) {
            Column(
                Modifier.align(Alignment.CenterEnd).padding(end = s.dp(2f)),
                verticalArrangement = Arrangement.spacedBy(s.dp(1.2f))
            ) {
                RoundButton(s, "+") { binder.zoomBy(1.0) }
                RoundButton(s, "−") { binder.zoomBy(-1.0) }
                if (!model.following && model.location != null && model.mode != Mode.PREVIEW) {
                    RoundButton(s, "◎") { model.following = true }
                }
            }
        }

        // Location status, most important first (H2, M2).
        when {
            model.permission != LocationPermission.GRANTED -> PermissionCard(
                s, blocked = model.permission == LocationPermission.BLOCKED,
                onRequest = onRequestPermission, onOpenSettings = onOpenAppSettings,
                modifier = Modifier.align(Alignment.Center)
            )
            !model.gpsEnabled -> Pill(s, "Location is off — turn it on in Settings",
                Modifier.align(Alignment.TopCenter).padding(top = s.dp(2.2f)))
            model.location == null -> Pill(s, "Waiting for GPS…",
                Modifier.align(Alignment.TopCenter).padding(top = s.dp(2.2f)))
        }
        model.message?.let {
            Pill(s, it, Modifier.align(Alignment.BottomCenter).padding(bottom = s.dp(9f))
                .clickable { model.message = null })
        }
        // Minimal music player + booster (via CarDash), bottom-right.
        if (hub != null && model.mode != Mode.SEARCH) {
            MiniPlayer(s, hub, Modifier.align(Alignment.BottomEnd).padding(s.dp(2f)))
        }
        Text(
            "© OpenStreetMap contributors",
            color = Faint,
            fontSize = s.sp(0.75f),
            modifier = Modifier.align(Alignment.BottomCenter).padding(s.dp(0.6f))
        )
    }
}

// ---- Browse ----------------------------------------------------------------

@Composable
private fun SearchPill(s: Scale, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .width(s.dp(34f))
            .clip(RoundedCornerShape(s.dp(3f)))
            .background(Panel)
            .border(1.dp, Line, RoundedCornerShape(s.dp(3f)))
            .clickable { onClick() }
            .padding(horizontal = s.dp(1.8f), vertical = s.dp(1.3f)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SearchGlyph(s.dp(1.8f))
        Spacer(Modifier.width(s.dp(1.2f)))
        Text("Where to?", color = Muted, fontSize = s.sp(1.8f))
    }
}

@Composable
private fun SpeedBadge(s: Scale, kmh: Int, modifier: Modifier) {
    Column(
        modifier
            .size(s.dp(8.5f))
            .clip(CircleShape)
            .background(Panel)
            .border(1.dp, Line, CircleShape),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("$kmh", color = Fg, fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = s.sp(2.6f))
        Text("km/h", color = Muted, fontSize = s.sp(0.9f))
    }
}

// ---- Search ----------------------------------------------------------------

@Composable
private fun SearchPanel(s: Scale, model: NavModel, modifier: Modifier) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    // The previous search is kept but fully selected, so typing replaces it and
    // the cursor is never stranded at the start of old text (L1).
    var field by remember {
        mutableStateOf(TextFieldValue(model.query, selection = TextRange(0, model.query.length)))
    }

    Column(
        modifier
            .fillMaxHeight()
            .width(s.dp(42f))
            .background(Panel)
            .padding(s.dp(1.6f))
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.clip(RoundedCornerShape(6.dp)).clickable { model.closeSearch() }.padding(s.dp(0.8f))
            ) { Text("‹", color = Fg, fontSize = s.sp(3f)) }
            Spacer(Modifier.width(s.dp(0.8f)))
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(s.dp(2.5f)))
                    .border(1.dp, Line, RoundedCornerShape(s.dp(2.5f)))
                    .padding(horizontal = s.dp(1.5f), vertical = s.dp(1.1f))
            ) {
                if (field.text.isEmpty()) Text("Search places in Pakistan", color = Faint, fontSize = s.sp(1.7f))
                BasicTextField(
                    value = field,
                    onValueChange = { field = it; model.query = it.text },
                    singleLine = true,
                    textStyle = TextStyle(color = Fg, fontSize = s.sp(1.7f)),
                    cursorBrush = SolidColor(Fg),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        focusManager.clearFocus()
                        model.submitSearch()
                    }),
                    modifier = Modifier.fillMaxWidth().padding(end = s.dp(3f)).focusRequester(focus)
                )
                if (field.text.isNotEmpty()) Box(
                    Modifier.align(Alignment.CenterEnd).clip(CircleShape)
                        .clickable { field = TextFieldValue(""); model.query = ""; focus.requestFocus() }
                        .padding(horizontal = s.dp(0.6f))
                ) { Text("×", color = Muted, fontSize = s.sp(2f)) }
            }
            Spacer(Modifier.width(s.dp(0.8f)))
            Box(
                Modifier
                    .clip(RoundedCornerShape(s.dp(2.5f)))
                    .background(Fg)
                    .clickable { focusManager.clearFocus(); model.submitSearch() }
                    .padding(horizontal = s.dp(1.6f), vertical = s.dp(1.1f))
            ) { Text("GO", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = s.sp(1.6f)) }
        }
        Spacer(Modifier.height(s.dp(1.2f)))

        val showingRecents = model.results.isEmpty() && !model.searched
        when {
            model.busy -> Note(s, "Searching…")
            model.searchError != null -> Note(s, model.searchError!!)
            model.searched && model.results.isEmpty() -> Note(s, "No places found. Try a different spelling or add the city name.")
            showingRecents && model.recents.isEmpty() -> Note(s, "Type a place, area or address, then press GO.")
        }
        if (showingRecents && model.recents.isNotEmpty()) {
            Text("RECENT", color = Faint, fontSize = s.sp(1f), letterSpacing = s.sp(0.1f),
                modifier = Modifier.padding(vertical = s.dp(0.6f)))
        }
        LazyColumn {
            items(if (showingRecents) model.recents else model.results) { place ->
                PlaceRow(s, place, model.location) { model.choose(place) }
            }
        }
    }
}

@Composable
private fun PlaceRow(s: Scale, place: Place, here: LatLon?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = s.dp(1.1f)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(place.name, color = Fg, fontWeight = FontWeight.SemiBold, fontSize = s.sp(1.6f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (place.detail.isNotBlank()) Text(place.detail, color = Muted, fontSize = s.sp(1.15f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        here?.let {
            Spacer(Modifier.width(s.dp(1f)))
            Text(Geo.formatDistance(Geo.distance(it, place.location)), color = Muted, fontSize = s.sp(1.2f))
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
}

// ---- Preview ---------------------------------------------------------------

@Composable
private fun PreviewCard(s: Scale, model: NavModel, modifier: Modifier) {
    val dest = model.destination ?: return
    Card(s, modifier.width(s.dp(40f))) {
        Text(dest.name, color = Fg, fontWeight = FontWeight.SemiBold, fontSize = s.sp(2.1f),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (dest.detail.isNotBlank()) Text(dest.detail, color = Muted, fontSize = s.sp(1.15f),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(s.dp(1.4f)))
        val r = model.route
        when {
            model.routeError != null -> Text(model.routeError!!, color = Fg, fontSize = s.sp(1.4f))
            r == null -> Text(if (model.location == null) "Waiting for GPS…" else "Finding the best route…",
                color = Muted, fontSize = s.sp(1.4f))
            else -> {
                Text("${Geo.formatDuration(r.duration)} · ${Geo.formatDistance(r.distance)}",
                    color = Fg, fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = s.sp(2.2f))
                Text("Arrive ${clock(r.duration)}", color = Muted, fontSize = s.sp(1.3f))
            }
        }
        Spacer(Modifier.height(s.dp(1.6f)))
        Row(horizontalArrangement = Arrangement.spacedBy(s.dp(1.2f))) {
            when {
                model.routeError != null -> FilledButton(s, "Try again") { model.computeRoute() }
                r != null -> FilledButton(s, "Start") { model.start() }
            }
            OutlineButton(s, "Cancel") { model.end() }
        }
    }
}

// ---- Navigating ------------------------------------------------------------

@Composable
private fun TurnBanner(s: Scale, model: NavModel, modifier: Modifier) {
    val r = model.route
    val p = model.progress
    // While the next line explains why, the old instructions are never shown (H3).
    val waitingNote = when {
        model.location == null -> "Waiting for GPS…"
        model.rerouting -> "Finding a new route…"
        model.rerouteNoRoute -> "No driving route from here yet — will try again"
        model.rerouteFailed -> "Can't reach the route server — retrying automatically"
        else -> "Finding a new route…"
    }
    Card(s, modifier.width(s.dp(44f))) {
        when {
            model.arrived -> Row(verticalAlignment = Alignment.CenterVertically) {
                TurnArrow(TurnIcon.ARRIVE, s.dp(7f))
                Spacer(Modifier.width(s.dp(1.6f)))
                Column {
                    Text("You've arrived", color = Fg, fontWeight = FontWeight.SemiBold, fontSize = s.sp(2.4f))
                    Text(model.destination?.name.orEmpty(), color = Muted, fontSize = s.sp(1.4f), maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
            }
            model.offRoute -> StatusBanner(s, "Off route", waitingNote)
            model.resuming || r == null || p == null -> StatusBanner(
                s, "Resuming trip to ${model.destination?.name.orEmpty()}",
                if (model.rerouteFailed || model.location == null) waitingNote else "Finding a route from here…"
            )
            else -> {
                val step = r.steps[p.nextStep]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TurnArrow(Instructions.icon(step), s.dp(7f))
                    Spacer(Modifier.width(s.dp(1.6f)))
                    Column {
                        Text(Geo.formatDistance(p.toNextStep), color = Fg, fontFamily = Orbitron,
                            fontWeight = FontWeight.Bold, fontSize = s.sp(3.4f))
                        Text(Instructions.text(step), color = Fg, fontSize = s.sp(1.7f), maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun TripBar(s: Scale, model: NavModel, modifier: Modifier) {
    val p = model.progress
    Card(s, modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            model.speedKmh?.takeIf { it >= 3 }?.let {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$it", color = Fg, fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = s.sp(2.6f))
                    Text("km/h", color = Muted, fontSize = s.sp(0.9f))
                }
                Spacer(Modifier.width(s.dp(2.2f)))
            }
            if (p != null && !model.arrived && !model.offRoute && !model.resuming && model.route != null) {
                Column {
                    Text(clock(p.remainingDuration), color = Fg, fontFamily = Orbitron,
                        fontWeight = FontWeight.Bold, fontSize = s.sp(2.2f))
                    Text("${Geo.formatDuration(p.remainingDuration)} · ${Geo.formatDistance(p.remainingDistance)}",
                        color = Muted, fontSize = s.sp(1.25f))
                }
                Spacer(Modifier.width(s.dp(2.4f)))
            }
            if ((model.offRoute || model.resuming || model.route == null) && !model.arrived) {
                // Old ETA would be wrong — show where we're heading and how far
                // it is in a straight line, so there's still something useful (M4).
                Column(Modifier.widthIn(max = s.dp(20f))) {
                    model.directDistance?.let {
                        Text(Geo.formatDistance(it), color = Fg, fontFamily = Orbitron,
                            fontWeight = FontWeight.Bold, fontSize = s.sp(2.2f))
                    }
                    Text(
                        (model.destination?.name.orEmpty()) + if (model.directDistance != null) " · direct" else "",
                        color = Muted, fontSize = s.sp(1.25f), maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(s.dp(2.4f)))
            }
            FilledButton(s, if (model.arrived) "Done" else "End") { model.end() }
        }
    }
}

/** Banner for states with no valid instruction: big title, reason underneath. */
@Composable
private fun StatusBanner(s: Scale, title: String, note: String) {
    Column {
        Text(title, color = Fg, fontWeight = FontWeight.SemiBold, fontSize = s.sp(2.4f), maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        Text(note, color = Muted, fontSize = s.sp(1.5f))
    }
}

/** Shown instead of "Waiting for GPS…" when CarNav may not use location (M2). */
@Composable
private fun PermissionCard(
    s: Scale,
    blocked: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier
) {
    Card(s, modifier.width(s.dp(46f))) {
        Text("Location permission needed", color = Fg, fontWeight = FontWeight.SemiBold, fontSize = s.sp(2.1f))
        Spacer(Modifier.height(s.dp(0.6f)))
        Text(
            if (blocked) "Location access is turned off for CarNav. Open Settings, choose Permissions → Location, and allow it."
            else "CarNav needs your location to show where you are and to give directions.",
            color = Muted, fontSize = s.sp(1.35f)
        )
        Spacer(Modifier.height(s.dp(1.6f)))
        if (blocked) FilledButton(s, "Open settings", onOpenSettings)
        else FilledButton(s, "Allow location", onRequest)
    }
}

// ---- Building blocks -------------------------------------------------------

@Composable
private fun Card(s: Scale, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(s.dp(1.4f)))
            .background(Panel)
            .border(1.dp, Line, RoundedCornerShape(s.dp(1.4f)))
            .padding(s.dp(1.8f)),
        content = content
    )
}

@Composable
private fun FilledButton(s: Scale, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(s.dp(3f)))
            .background(Fg)
            .clickable { onClick() }
            .padding(horizontal = s.dp(2.8f), vertical = s.dp(1.1f))
    ) { Text(label.uppercase(), color = Color.Black, fontWeight = FontWeight.Bold, fontSize = s.sp(1.4f)) }
}

@Composable
private fun OutlineButton(s: Scale, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(s.dp(3f)))
            .border(1.dp, Line, RoundedCornerShape(s.dp(3f)))
            .clickable { onClick() }
            .padding(horizontal = s.dp(2.8f), vertical = s.dp(1.1f))
    ) { Text(label.uppercase(), color = Fg, fontWeight = FontWeight.SemiBold, fontSize = s.sp(1.4f)) }
}

@Composable
private fun RoundButton(s: Scale, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(s.dp(5.4f))
            .clip(CircleShape)
            .background(Panel)
            .border(1.dp, Line, CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) { Text(label, color = Fg, fontSize = s.sp(2.4f)) }
}

@Composable
private fun Pill(s: Scale, text: String, modifier: Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(s.dp(3f)))
            .background(Panel)
            .border(1.dp, Line, RoundedCornerShape(s.dp(3f)))
            .padding(horizontal = s.dp(2f), vertical = s.dp(0.9f))
    ) { Text(text, color = Fg, fontSize = s.sp(1.3f)) }
}

@Composable
private fun Note(s: Scale, text: String) =
    Text(text, color = Muted, fontSize = s.sp(1.3f), modifier = Modifier.padding(vertical = s.dp(0.8f)))

@Composable
private fun SearchGlyph(size: Dp) = Canvas(Modifier.size(size)) {
    val u = this.size.minDimension / 24f
    drawCircle(Muted, radius = 7f * u, center = Offset(10f * u, 10f * u), style = Stroke(2.2f * u))
    drawLine(Muted, Offset(15.5f * u, 15.5f * u), Offset(21f * u, 21f * u), 2.4f * u, StrokeCap.Round)
}

/** Big white turn arrow, drawn on a 24×24 grid. */
@Composable
fun TurnArrow(icon: TurnIcon, size: Dp) = Canvas(Modifier.size(size)) {
    val u = this.size.minDimension / 24f
    val w = 2.6f * u
    fun pt(x: Float, y: Float) = Offset(x * u, y * u)
    fun polyArrow(vararg xy: Float) {
        val pts = xy.toList().chunked(2).map { pt(it[0], it[1]) }
        for (i in 0 until pts.size - 1) drawLine(Fg, pts[i], pts[i + 1], w, StrokeCap.Round)
        // Arrowhead on the last segment.
        val a = pts[pts.size - 2]; val b = pts.last()
        val ang = atan2(b.y - a.y, b.x - a.x)
        val len = 5.5f * u
        // Two wings pointing back from the tip, ~37° either side of the shaft.
        for (side in listOf(2.5f, -2.5f)) {
            val t = ang + side
            drawLine(Fg, b, Offset(b.x + len * cos(t), b.y + len * sin(t)), w, StrokeCap.Round)
        }
    }
    when (icon) {
        TurnIcon.STRAIGHT -> polyArrow(12f, 22f, 12f, 3f)
        TurnIcon.LEFT -> polyArrow(15f, 22f, 15f, 11f, 4f, 11f)
        TurnIcon.RIGHT -> polyArrow(9f, 22f, 9f, 11f, 20f, 11f)
        TurnIcon.SLIGHT_LEFT -> polyArrow(14f, 22f, 14f, 13f, 6f, 5f)
        TurnIcon.SLIGHT_RIGHT -> polyArrow(10f, 22f, 10f, 13f, 18f, 5f)
        TurnIcon.SHARP_LEFT -> polyArrow(15f, 22f, 15f, 9f, 6f, 17f)
        TurnIcon.SHARP_RIGHT -> polyArrow(9f, 22f, 9f, 9f, 18f, 17f)
        TurnIcon.UTURN -> polyArrow(16f, 22f, 16f, 9f, 15.4f, 6.4f, 14f, 5f, 12f, 4.5f, 10f, 5f, 8.6f, 6.4f, 8f, 9f, 8f, 17f)
        TurnIcon.ROUNDABOUT -> {
            drawCircle(Fg, radius = 4.5f * u, center = pt(12f, 12f), style = Stroke(w))
            drawLine(Fg, pt(12f, 22f), pt(12f, 16.5f), w, StrokeCap.Round)
            polyArrow(12f, 7.5f, 12f, 2.5f)
        }
        TurnIcon.ARRIVE -> {
            drawCircle(Fg, radius = 7f * u, center = pt(12f, 12f), style = Stroke(w))
            drawCircle(Fg, radius = 2.6f * u, center = pt(12f, 12f))
        }
    }
}

/** Clock time `seconds` from now, e.g. "3:42 PM". */
private fun clock(secondsFromNow: Double): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(System.currentTimeMillis() + (secondsFromNow * 1000).toLong()))

// ---- Mini music player -------------------------------------------------------

/**
 * Song, ⏮ ⏯ ⏭ and a booster bar. CarDash does the real work (see [MusicHub]);
 * this polls it once a second while CarNav is on screen. Hidden if CarDash
 * can't be reached.
 */
@Composable
private fun MiniPlayer(s: Scale, hub: MusicHub, modifier: Modifier) {
    var state by remember { mutableStateOf<MusicState?>(null) }
    var boost by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(hub) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val st = hub.state()
                state = st
                if (st != null && !dragging) boost = st.boost
                delay(1000)
            }
        }
    }
    // Booster changes: the latest value wins, at most ~10 updates a second.
    val boostTarget = remember { MutableStateFlow<Float?>(null) }
    LaunchedEffect(hub) {
        boostTarget.filterNotNull().conflate().collect { v ->
            hub.setBoost(v)?.let { st -> state = st }
            delay(100)
        }
    }

    val st = state ?: return
    Card(s, modifier.width(s.dp(31f))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (st.hasMedia) st.title ?: "Unknown track" else "No music playing",
                    color = if (st.hasMedia) Fg else Muted, fontWeight = FontWeight.SemiBold,
                    fontSize = s.sp(1.35f), maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (st.hasMedia && st.artist != null) Text(
                    st.artist, color = Muted, fontSize = s.sp(1.1f), maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            if (st.hasMedia) {
                fun send(action: String) = scope.launch { hub.transport(action)?.let { state = it } }
                MiniButton(s, MiniIcon.PREV) { send("prev") }
                MiniButton(s, if (st.playing) MiniIcon.PAUSE else MiniIcon.PLAY, filled = true) {
                    state = st.copy(playing = !st.playing) // instant feedback; the next poll confirms
                    send(if (st.playing) "pause" else "play")
                }
                MiniButton(s, MiniIcon.NEXT) { send("next") }
            }
        }
        Spacer(Modifier.height(s.dp(1.1f)))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("BOOST", color = Muted, fontWeight = FontWeight.SemiBold, fontSize = s.sp(0.95f),
                letterSpacing = s.sp(0.08f))
            Spacer(Modifier.width(s.dp(1.2f)))
            BoostBar(
                s, boost,
                onDrag = { dragging = it },
                onChange = { v -> boost = v; boostTarget.value = v },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(s.dp(1.2f)))
            Text(
                when {
                    !st.boostSupported && boost > 0f -> "N/A"
                    boost <= 0f -> "OFF"
                    else -> "${(boost * 100).roundToInt()}%"
                },
                color = if (boost > 0f) Fg else Muted, fontWeight = FontWeight.SemiBold,
                fontSize = s.sp(1.1f), modifier = Modifier.widthIn(min = s.dp(4f))
            )
        }
    }
}

/** Horizontal booster bar: tap or drag. Far left (snaps) = off, far right = max. */
@Composable
private fun BoostBar(
    s: Scale,
    value: Float,
    onDrag: (Boolean) -> Unit,
    onChange: (Float) -> Unit,
    modifier: Modifier
) {
    var widthPx by remember { mutableStateOf(1) }
    val latest by rememberUpdatedState(onChange)
    val latestDrag by rememberUpdatedState(onDrag)
    fun valueAt(x: Float): Float {
        val v = (x / widthPx).coerceIn(0f, 1f)
        return if (v < 0.03f) 0f else (v * 100f).roundToInt() / 100f
    }
    val thumb = s.dp(2.2f)
    BoxWithConstraints(
        modifier
            .height(s.dp(3.2f)) // tall touch target around a thin bar
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) { detectTapGestures { latest(valueAt(it.x)) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { latestDrag(true); latest(valueAt(it.x)) },
                    onDragEnd = { latestDrag(false) },
                    onDragCancel = { latestDrag(false) }
                ) { change, _ ->
                    change.consume()
                    latest(valueAt(change.position.x))
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        val track = maxWidth
        Box(Modifier.fillMaxWidth().height(s.dp(0.6f)).clip(RoundedCornerShape(50)).background(Line)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(value).background(Fg))
        }
        Box(
            Modifier
                .offset(x = (track - thumb) * value)
                .size(thumb)
                .clip(CircleShape)
                .background(if (value > 0f) Fg else Color.Black)
                .border(1.dp, if (value > 0f) Fg else Line, CircleShape)
        )
    }
}

private enum class MiniIcon { PLAY, PAUSE, PREV, NEXT }

@Composable
private fun MiniButton(s: Scale, icon: MiniIcon, filled: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(start = s.dp(0.6f))
            .size(s.dp(4.2f))
            .clip(CircleShape)
            .background(if (filled) Fg else Color.Transparent)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        val c = if (filled) Color.Black else Fg
        Canvas(Modifier.size(s.dp(1.7f))) {
            val u = size.minDimension / 24f
            fun tri(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) = drawPath(
                Path().apply { moveTo(x1 * u, y1 * u); lineTo(x2 * u, y2 * u); lineTo(x3 * u, y3 * u); close() }, c
            )
            when (icon) {
                MiniIcon.PLAY -> tri(6f, 3f, 21f, 12f, 6f, 21f)
                MiniIcon.PAUSE -> {
                    drawRect(c, Offset(5f * u, 3f * u), androidx.compose.ui.geometry.Size(5f * u, 18f * u))
                    drawRect(c, Offset(14f * u, 3f * u), androidx.compose.ui.geometry.Size(5f * u, 18f * u))
                }
                MiniIcon.PREV -> {
                    tri(21f, 3f, 9f, 12f, 21f, 21f)
                    drawRect(c, Offset(4f * u, 3f * u), androidx.compose.ui.geometry.Size(3.5f * u, 18f * u))
                }
                MiniIcon.NEXT -> {
                    tri(3f, 3f, 15f, 12f, 3f, 21f)
                    drawRect(c, Offset(16.5f * u, 3f * u), androidx.compose.ui.geometry.Size(3.5f * u, 18f * u))
                }
            }
        }
    }
}
