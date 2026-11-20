package com.moeez.carnav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavLogicTest {

    private fun fixture(name: String) =
        javaClass.classLoader!!.getResource(name)!!.readText()

    private val route by lazy { Api.parseRoute(fixture("route_lahore.json")) }

    @Test fun parsesRealLahoreRoute() {
        assertTrue(route.points.size > 100)
        assertTrue(route.steps.size > 10)
        assertEquals("depart", route.steps.first().type)
        assertEquals("arrive", route.steps.last().type)
        // Our own measured length should match the server's distance closely.
        assertEquals(route.distance, route.length, route.distance * 0.03)
        // Manoeuvres are in order along the line.
        for (k in 1 until route.stepAlong.size) assertTrue(route.stepAlong[k] >= route.stepAlong[k - 1])
    }

    @Test fun trackerFollowsASimulatedDrive() {
        val tracker = RouteTracker(route)
        var lastAlong = -1.0
        var lastStep = 0
        // "Drive" the route, one fix every ~10th point, slightly off the line like real GPS.
        for (i in route.points.indices step 10) {
            val p = route.points[i]
            val pr = tracker.update(LatLon(p.lat + 0.00003, p.lon + 0.00003)) // ~4 m off
            assertTrue("along must not go backwards", pr.along >= lastAlong - 1)
            assertTrue("upcoming step must not go backwards", pr.nextStep >= lastStep)
            assertTrue("GPS noise isn't 'off route'", pr.offRoute < 15)
            lastAlong = pr.along; lastStep = pr.nextStep
        }
        val end = tracker.update(route.points.last())
        assertTrue(end.arrived)
        assertEquals(route.steps.lastIndex, end.nextStep)
    }

    @Test fun startOfRouteShowsFirstRealTurnNotDepart() {
        val pr = RouteTracker(route).update(route.points.first())
        assertEquals(1, pr.nextStep)
        assertEquals(route.stepAlong[1], pr.toNextStep, 2.0)
        assertFalse(pr.arrived)
        assertEquals(route.duration, pr.remainingDuration, route.duration * 0.02)
    }

    @Test fun detectsLeavingTheRoute() {
        val tracker = RouteTracker(route)
        tracker.update(route.points[50])
        val p = route.points[50]
        val off = tracker.update(LatLon(p.lat + 0.003, p.lon)) // ~330 m north
        assertTrue("expected off route, got ${off.offRoute}", off.offRoute > 100)
    }

    @Test fun everyRealStepGetsSensibleText() {
        for (s in route.steps) {
            val t = Instructions.text(s)
            assertTrue("empty text for $s", t.isNotBlank())
            assertFalse("leaked code in '$t'", t.contains("null") || t.contains("  "))
        }
    }

    @Test fun instructionWording() {
        fun step(type: String, mod: String?, name: String = "", ref: String? = null, exit: Int? = null) =
            Step(type, mod, name, ref, exit, LatLon(0.0, 0.0), 0.0, 0.0)
        assertEquals("Turn left onto Ferozepur Road", Instructions.text(step("turn", "left", "Ferozepur Road")))
        assertEquals("Bear right", Instructions.text(step("turn", "slight right")))
        assertEquals("At the roundabout, take the 2nd exit onto Mall Road",
            Instructions.text(step("roundabout", "right", "Mall Road", exit = 2)))
        assertEquals("Make a U-turn", Instructions.text(step("continue", "uturn")))
        assertEquals("Take the exit onto Ring Road (N-5)", Instructions.text(step("off ramp", "slight left", "Ring Road", "N-5")))
        assertEquals("Arrive at your destination", Instructions.text(step("arrive", "right")))
        assertEquals(TurnIcon.SHARP_LEFT, Instructions.icon(step("turn", "sharp left")))
        assertEquals(TurnIcon.ROUNDABOUT, Instructions.icon(step("rotary", "straight")))
        assertEquals(TurnIcon.ARRIVE, Instructions.icon(step("arrive", "left")))
    }

    @Test fun searchIsEnglishAndDeduplicated() {
        val places = Api.parseSearch(fixture("search_emporium.json"))
        assertTrue(places.isNotEmpty())
        assertEquals("Emporium Mall", places.first().name)
        assertEquals("near-duplicates should collapse", 1, places.count { it.name == "Emporium Mall" })
        assertFalse(places.first().detail.contains("Pakistan"))
        assertTrue(places.first().detail.contains("Johar Town"))
    }

    @Test fun formatting() {
        assertEquals("350 m", Geo.formatDistance(347.0))
        assertEquals("1.2 km", Geo.formatDistance(1234.0))
        assertEquals("14 km", Geo.formatDistance(13_700.0))
        assertEquals("18 min", Geo.formatDuration(1104.0))
        assertEquals("1 h 5 min", Geo.formatDuration(3900.0))
        assertEquals("1 min", Geo.formatDuration(20.0))
    }
}
