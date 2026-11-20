package com.moeez.carnav

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.view.MotionEvent
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import java.io.File
import kotlin.math.hypot

/**
 * Owns the osmdroid MapView. osmdroid draws with a plain Canvas (no OpenGL ES 3),
 * so it runs on the head unit where Organic Maps couldn't.
 */
class MapBinder(context: Context) {

    val map: MapView
    var onUserPan: () -> Unit = {}

    private val density = context.resources.displayMetrics.density
    private val routeLine: Polyline
    private val routeCasing: Polyline
    private val position = PositionOverlay()
    private val destination = DestinationOverlay()
    private var lastZoom = -1.0
    private var centredOnce = false

    init {
        Configuration.getInstance().apply {
            userAgentValue = Api.USER_AGENT
            osmdroidBasePath = File(context.filesDir, "osmdroid")
            // In app storage, not the cache dir: Android empties the cache dir
            // when storage runs low, which would silently drop the offline map
            // of places already driven (L8). Size-capped by osmdroid itself.
            osmdroidTileCache = File(context.filesDir, "tiles")
            tileFileSystemCacheMaxBytes = 300L * 1024 * 1024
            tileFileSystemCacheTrimBytes = 250L * 1024 * 1024
        }
        // Tiles cached by v0.3 and earlier lived in the cache dir; free that space.
        val oldCache = File(context.cacheDir, "tiles")
        if (oldCache.exists()) Thread { oldCache.deleteRecursively() }.start()

        map = MapView(context).apply {
            // OpenStreetMap's German community style (free, no key): unlike the
            // standard style it gives every label a Latin-script name, so places
            // whose OSM name is Urdu-only still read in English ("Shadman",
            // "Shah Jamal"). Shown in black-and-white night colours to match CarDash.
            setTileSource(LATIN_TILES)
            overlayManager.tilesOverlay.setColorFilter(NIGHT_FILTER)
            overlayManager.tilesOverlay.loadingBackgroundColor = Color.BLACK
            overlayManager.tilesOverlay.loadingLineColor = Color.rgb(30, 30, 30)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 5.0
            maxZoomLevel = 19.0
            isTilesScaledToDpi = true
            setBackgroundColor(Color.BLACK)
            // Keep the map inside Pakistan (with a small margin).
            setScrollableAreaLimitDouble(
                BoundingBox(
                    Api.PAKISTAN_NORTH + 1, Api.PAKISTAN_EAST + 1,
                    Api.PAKISTAN_SOUTH - 1, Api.PAKISTAN_WEST - 1
                )
            )
            controller.setZoom(12.0)
            controller.setCenter(GeoPoint(31.5204, 74.3587)) // Lahore until GPS arrives
        }

        routeCasing = Polyline(map).apply {
            outlinePaint.color = Color.BLACK
            outlinePaint.strokeWidth = 13f * density
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
            isEnabled = false
        }
        routeLine = Polyline(map).apply {
            outlinePaint.color = Color.WHITE
            outlinePaint.strokeWidth = 8f * density
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
            isEnabled = false
        }
        map.overlays.add(routeCasing)
        map.overlays.add(routeLine)
        map.overlays.add(destination)
        map.overlays.add(position)
        installPanDetector()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installPanDetector() {
        var downX = 0f
        var downY = 0f
        var fired = false
        map.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; fired = false }
                MotionEvent.ACTION_POINTER_DOWN -> if (!fired) { fired = true; onUserPan() } // pinch
                MotionEvent.ACTION_MOVE -> if (!fired && hypot(ev.x - downX, ev.y - downY) > 24 * density) {
                    fired = true
                    onUserPan()
                }
            }
            false // let osmdroid handle the gesture
        }
    }

    fun showRoute(route: Route?) {
        val pts = route?.points?.map { GeoPoint(it.lat, it.lon) } ?: emptyList()
        routeCasing.setPoints(pts)
        routeLine.setPoints(pts)
        routeCasing.isEnabled = pts.isNotEmpty()
        routeLine.isEnabled = pts.isNotEmpty()
        map.invalidate()
    }

    fun showDestination(p: LatLon?) {
        destination.point = p?.let { GeoPoint(it.lat, it.lon) }
        map.invalidate()
    }

    fun showPosition(p: LatLon?, heading: Float) {
        position.point = p?.let { GeoPoint(it.lat, it.lon) }
        position.heading = heading
        map.invalidate()
    }

    /** Centre on the car. zoom = null keeps the current zoom. */
    fun follow(p: LatLon, zoom: Double?) {
        // First GPS fix: jump from the city-wide default to street level.
        val z = zoom ?: if (!centredOnce) 15.0 else null
        centredOnce = true
        if (z != null && z != lastZoom) {
            map.controller.setZoom(z)
            lastZoom = z
        }
        map.controller.setCenter(GeoPoint(p.lat, p.lon))
    }

    fun fitRoute(route: Route) {
        val north = route.points.maxOf { it.lat }
        val south = route.points.minOf { it.lat }
        val east = route.points.maxOf { it.lon }
        val west = route.points.minOf { it.lon }
        val latSpan = (north - south).coerceAtLeast(0.004)
        val lonSpan = (east - west).coerceAtLeast(0.004)
        // The preview card covers the bottom-left of the screen and the zoom
        // buttons the right edge, so widen the box on those sides: the route
        // then sits in the clear upper-right area instead of under the card.
        val box = BoundingBox(
            north + latSpan * 0.08,
            east + lonSpan * 0.15,
            south - latSpan * 0.55,
            west - lonSpan * 0.85
        )
        lastZoom = -1.0
        // Wait for layout so the map knows its size.
        map.post { map.zoomToBoundingBox(box, false, (24 * density).toInt()) }
    }

    fun zoomBy(delta: Double) {
        lastZoom = -1.0
        map.controller.setZoom(map.zoomLevelDouble + delta)
    }

    fun onResume() = map.onResume()
    fun onPause() = map.onPause()
    /** Stops the tile threads of a map whose activity is gone (recreation, exit). */
    fun onDestroy() = map.onDetach()

    // ---- Overlays -----------------------------------------------------------

    /** The car: a white arrow pointing the way you're driving. */
    private inner class PositionOverlay : Overlay() {
        var point: GeoPoint? = null
        var heading = 0f
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 3f * density
            strokeJoin = Paint.Join.ROUND
        }
        private val px = Point()

        override fun draw(c: Canvas, mapView: MapView, shadow: Boolean) {
            if (shadow) return
            val p = point ?: return
            mapView.projection.toPixels(p, px)
            val s = 15f * density
            val arrow = Path().apply {
                moveTo(0f, -s); lineTo(s * 0.8f, s); lineTo(0f, s * 0.45f); lineTo(-s * 0.8f, s); close()
            }
            c.save()
            c.translate(px.x.toFloat(), px.y.toFloat())
            c.rotate(heading - mapView.mapOrientation)
            c.drawPath(arrow, edge)
            c.drawPath(arrow, fill)
            c.restore()
        }
    }

    /** Destination: a white ring with a black centre. */
    private inner class DestinationOverlay : Overlay() {
        var point: GeoPoint? = null
        private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        private val px = Point()

        override fun draw(c: Canvas, mapView: MapView, shadow: Boolean) {
            if (shadow) return
            val p = point ?: return
            mapView.projection.toPixels(p, px)
            c.drawCircle(px.x.toFloat(), px.y.toFloat(), 13f * density, black)
            c.drawCircle(px.x.toFloat(), px.y.toFloat(), 11f * density, white)
            c.drawCircle(px.x.toFloat(), px.y.toFloat(), 4.5f * density, black)
        }
    }

    companion object {
        val LATIN_TILES = XYTileSource(
            "OSMde", 0, 19, 256, ".png",
            arrayOf("https://tile.openstreetmap.de/"),
            "© OpenStreetMap contributors"
        )

        /** Greyscale, then invert: OSM's light map becomes a dark monochrome one. */
        private val NIGHT_FILTER = ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    -0.299f, -0.587f, -0.114f, 0f, 255f,
                    -0.299f, -0.587f, -0.114f, 0f, 255f,
                    -0.299f, -0.587f, -0.114f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
    }
}
