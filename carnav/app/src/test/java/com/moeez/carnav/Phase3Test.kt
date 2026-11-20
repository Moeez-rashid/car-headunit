package com.moeez.carnav

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

// ---- Helpers (file-private copies; see ReliabilityTest.kt) -----------------

private class Store3 : KeyValueStore {
    val map = HashMap<String, String>()
    override fun getString(key: String) = map[key]
    override fun putString(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}

/** Point [eastM] metres east and [northM] metres north of this one. */
private fun LatLon.at(eastM: Double, northM: Double) =
    LatLon(lat + northM / 111_320.0, lon + eastM / (111_320.0 * Math.cos(Math.toRadians(lat))))

private fun routeThrough(vararg pts: LatLon): Route {
    val tmp = Route(pts.toList(), 0.0, 0.0, emptyList())
    val steps = listOf(
        Step("depart", null, "Start Road", null, null, pts.first(), tmp.length, tmp.length / 12),
        Step("arrive", null, "", null, null, pts.last(), 0.0, 0.0)
    )
    return Route(pts.toList(), tmp.length, tmp.length / 12, steps)
}

private val O = LatLon(31.5000, 74.3000)
private const val EAST = 90f
private const val WEST = 270f

/** Divided road: east 500 m on the south side, U-turn, west 500 m on the north side, 20 m apart. */
private val DIVIDED = routeThrough(O, O.at(500.0, 0.0), O.at(500.0, 20.0), O.at(0.0, 20.0))

/** Drive [route] from [fromM] to [toM] metres along at [stepM] per 1 s fix; returns every progress. */
private fun drive(
    t: RouteTracker, route: Route, fromM: Double, toM: Double, stepM: Double,
    bearingOf: (Double) -> Float?, noise: (Double) -> Pair<Double, Double> = { 0.0 to 0.0 }
): List<Pair<Double, Progress>> {
    val out = ArrayList<Pair<Double, Progress>>()
    var m = fromM
    while (m <= toM + 1e-6) {
        val (e, n) = noise(m)
        out += m to t.update(route.pointAt(m).at(e, n), 1.0, bearingOf(m))
        m += stepM
    }
    return out
}

private fun assertTracksTruth(log: List<Pair<Double, Progress>>, tolerance: Double = 25.0) {
    for ((m, pr) in log) {
        if (Math.abs(pr.along - m) > tolerance) fail("at $m m the tracker said ${pr.along} m")
        assertFalse("arrived early at $m m", pr.arrived && m < log.last().first - 30)
    }
}

// ---- M5: map matching on overlapping / parallel / U-turn routes -------------

class MapMatchingTest {

    /** 1. Out and back along the SAME road (dead end turnaround): exactly overlapping lines. */
    @Test fun selfOverlappingRouteFollowsTheRightPass() {
        val route = routeThrough(O, O.at(400.0, 0.0), O.at(100.0, 0.0))
        for (withHeading in listOf(true, false)) {
            val t = RouteTracker(route)
            t.update(route.pointAt(0.0))
            val log = drive(t, route, 10.0, 690.0, 12.0, bearingOf = { m ->
                if (!withHeading) null else if (m < 400) EAST else WEST
            })
            assertTracksTruth(log)
            val last = log.last().second
            assertTrue("reached the end (heading=$withHeading)", last.arrived)
        }
    }

    /** 1b. A route that crosses itself (figure of eight): the crossing mustn't skip or repeat a loop. */
    @Test fun crossingRoutePicksTheCurrentPass() {
        val route = routeThrough(
            O, O.at(300.0, 0.0), O.at(300.0, 200.0), O.at(150.0, 200.0),
            O.at(150.0, -200.0), O.at(0.0, -200.0)
        )
        val t = RouteTracker(route)
        t.update(route.pointAt(0.0))
        // No heading: the crossing at (150, 0) is the hard case.
        assertTracksTruth(drive(t, route, 10.0, route.length, 10.0, bearingOf = { null }))
    }

    /** 2. U-turn on a divided road, with GPS heading. */
    @Test fun uTurnOnADividedRoad() {
        val t = RouteTracker(DIVIDED)
        t.update(DIVIDED.pointAt(0.0))
        val log = drive(t, DIVIDED, 10.0, DIVIDED.length, 10.0, bearingOf = { m ->
            when { m < 495 -> EAST; m < 525 -> 0f; else -> WEST }
        })
        assertTracksTruth(log)
        assertTrue(log.none { it.second.wrongWay })
        assertTrue("arrival only at the very end",
            log.filter { it.second.arrived }.all { it.first >= DIVIDED.length - RouteTracker.ARRIVE_M })
        assertTrue(log.last().second.arrived)
    }

    /** 3. Two parallel roads 30 m apart, both used by the route in the SAME direction. */
    @Test fun parallelRoadsSameDirection() {
        val route = routeThrough(
            O, O.at(500.0, 0.0), O.at(500.0, 200.0), O.at(0.0, 200.0),
            O.at(0.0, 30.0), O.at(500.0, 30.0)
        )
        val t = RouteTracker(route)
        t.update(route.pointAt(0.0))
        // First pass along the south road, GPS pulled 12 m towards the north road throughout.
        val log = drive(t, route, 10.0, 490.0, 12.0, bearingOf = { EAST }, noise = { 0.0 to 12.0 })
        assertTracksTruth(log)
        // …and the second pass along the north road is followed too.
        val second = drive(t, route, 500.0, route.length, 12.0, bearingOf = { null })
        assertTracksTruth(second)
    }

    /** 4. Wrong turn, a loop round the block, and back onto the route BEHIND where the car left it. */
    @Test fun legitimateBackwardRejoinAfterAWrongTurn() {
        val route = routeThrough(O, O.at(1500.0, 0.0))
        val t = RouteTracker(route)
        t.update(O)
        drive(t, route, 10.0, 300.0, 15.0, bearingOf = { EAST })
        // Wrong turn south at 300 m, loop west, come back up at 150 m.
        for (p in listOf(O.at(300.0, -40.0), O.at(300.0, -120.0), O.at(220.0, -120.0),
            O.at(150.0, -120.0), O.at(150.0, -60.0))) {
            val pr = t.update(p, 8.0, null)
            assertTrue("off the line while away", pr.offRoute > 40)
            assertEquals("progress held where the car left the route", 300.0, pr.along, 20.0)
        }
        val back = t.update(O.at(155.0, 2.0), 4.0, EAST)
        assertTrue("on the route again", back.offRoute < 10)
        assertEquals("accepted the earlier position", 155.0, back.along, 15.0)
        val log = drive(t, route, 170.0, 600.0, 15.0, bearingOf = { EAST })
        assertTracksTruth(log)
    }

    /** 5. Missing GPS bearing all the way round a U-turn. */
    @Test fun uTurnWithoutAnyBearing() {
        val t = RouteTracker(DIVIDED)
        t.update(DIVIDED.pointAt(0.0))
        val log = drive(t, DIVIDED, 10.0, DIVIDED.length, 10.0, bearingOf = { null })
        assertTracksTruth(log)
        assertTrue(log.none { it.second.wrongWay })
        assertTrue(log.filter { it.second.arrived }.all { it.first >= DIVIDED.length - RouteTracker.ARRIVE_M })
        assertTrue(log.last().second.arrived)
    }

    /** 6. One badly placed fix near the U-turn mustn't flip the car onto the return carriageway. */
    @Test fun temporaryInaccurateFixNearTheUTurn() {
        for (b in listOf<Float?>(EAST, null)) {
            val t = RouteTracker(DIVIDED)
            t.update(DIVIDED.pointAt(0.0))
            val log = drive(t, DIVIDED, 10.0, 460.0, 10.0, bearingOf = { b },
                noise = { m -> if (m == 400.0) 0.0 to 16.0 else 0.0 to 0.0 }) // 4 m from the far side
            assertTracksTruth(log)
        }
    }

    /** Driving against the route on its own line is reported as wrong way (heading known only). */
    @Test fun wrongWayNeedsAReliableHeading() {
        val route = routeThrough(O, O.at(1000.0, 0.0))
        val t = RouteTracker(route)
        t.update(O.at(400.0, 0.0))
        val pr = t.update(O.at(390.0, 0.0), 1.0, WEST)
        assertTrue(pr.wrongWay)
        assertFalse(pr.arrived)
        val t2 = RouteTracker(route)
        t2.update(O.at(400.0, 0.0))
        assertFalse(t2.update(O.at(390.0, 0.0), 1.0, null).wrongWay)
    }

    @Test fun angleDifference() {
        assertEquals(20.0, RouteTracker.angleDiff(350.0, 10.0), 1e-9)
        assertEquals(180.0, RouteTracker.angleDiff(90.0, 270.0), 1e-9)
        assertEquals(0.0, RouteTracker.angleDiff(-90.0, 270.0), 1e-9)
    }
}

// ---- M4: reroute time limits, failure kinds, no duplicate requests ---------

@OptIn(ExperimentalCoroutinesApi::class)
private class NavHarness(val scope: TestScope) {
    var now = 0L
    val store = Store3()
    /** What the next reroute does; default: straight-line route. */
    var reroute: suspend (LatLon, LatLon, Float?) -> Route = { a, b, _ -> routeThrough(a, b) }
    val rerouteCalls = ArrayList<Float?>()
    var inFlight = 0
    var maxInFlight = 0
    val model = NavModel(
        scope, store,
        router = { a, b -> routeThrough(a, b) },
        rerouter = { a, b, h ->
            rerouteCalls += h
            inFlight++; maxInFlight = maxOf(maxInFlight, inFlight)
            try { reroute(a, b, h) } finally { inFlight-- }
        },
        nameLookup = { null },
        clock = { now },
        wallClock = { 1_700_000_000_000L }
    )

    fun fix(p: LatLon, speed: Float? = null, bearing: Float? = null, idle: Boolean = true) {
        model.onFix(Fix(p, Fix.GPS, 5f, 0, now, speed, bearing))
        now += 1000
        if (idle) scope.advanceUntilIdle() else scope.runCurrent()
    }

    fun start(dest: LatLon) {
        fix(O)
        model.choose(Place("Dest", "", dest))
        scope.advanceUntilIdle()
        assertNotNull(model.route)
        model.start()
    }

    /**
     * From [x0] m along the (eastward) route: one fix on it, then three 60–80 m
     * off it → off route + reroute requested. Steps stay < 100 m per second so
     * the GPS glitch filter accepts them.
     */
    fun goOffRoute(x0: Double = 0.0, idle: Boolean = true, speed: Float? = null, bearing: Float? = null) {
        fix(O.at(x0 + 20, 0.0), speed, bearing, idle)
        for ((e, n) in listOf(40.0 to 60.0, 50.0 to 70.0, 60.0 to 80.0)) fix(O.at(x0 + e, n), speed, bearing, idle)
    }
}

class RerouteTest {
    private val dest = O.at(3000.0, 0.0)

    @Test fun stalledServerEndsAtTheDeadlineAndIsRetried() = runTest {
        val h = NavHarness(this)
        h.start(dest)
        h.reroute = { _, _, _ -> awaitCancellation() } // server never answers
        h.goOffRoute(idle = false)
        assertTrue(h.model.rerouting)
        advanceTimeBy(NavModel.REROUTE_DEADLINE_MS - 1000)
        assertTrue("still within the limit", h.model.rerouting)
        advanceTimeBy(2000)
        runCurrent()
        assertFalse("never stuck on 'Finding a new route…'", h.model.rerouting)
        assertTrue(h.model.rerouteFailed)
        assertFalse("a timeout is a connectivity problem", h.model.rerouteNoRoute)
        assertTrue("instructions stay hidden", h.model.offRoute)
        assertNotNull("distance to the destination still known", h.model.directDistance)
        // Server back: retried after the back-off and recovered.
        h.reroute = { a, b, _ -> routeThrough(a, b) }
        val calls = h.rerouteCalls.size
        h.now += NavModel.FIRST_RETRY_MS
        h.fix(O.at(70.0, 80.0))
        assertEquals(calls + 1, h.rerouteCalls.size)
        assertFalse(h.model.offRoute)
        assertFalse(h.model.rerouteFailed)
    }

    @Test fun noRouteIsReportedSeparatelyAndRetriedLater() = runTest {
        val h = NavHarness(this)
        h.start(dest)
        h.reroute = { _, _, _ -> throw NoRouteException() }
        h.goOffRoute()
        assertTrue(h.model.rerouteFailed)
        assertTrue(h.model.rerouteNoRoute)
        val calls = h.rerouteCalls.size
        h.now += NavModel.MAX_RETRY_MS // the connectivity back-off would have retried by now…
        h.fix(O.at(70.0, 80.0))
        assertEquals("…but 'no route' waits longer", calls, h.rerouteCalls.size)
        h.now += NavModel.NO_ROUTE_RETRY_MS
        h.fix(O.at(80.0, 80.0))
        assertEquals(calls + 1, h.rerouteCalls.size)
    }

    @Test fun offlineIsNotReportedAsNoRoute() = runTest {
        val h = NavHarness(this)
        h.start(dest)
        h.reroute = { _, _, _ -> throw IOException("offline") }
        h.goOffRoute()
        assertTrue(h.model.rerouteFailed)
        assertFalse(h.model.rerouteNoRoute)
    }

    @Test fun neverTwoReroutesAtOnce() = runTest {
        val h = NavHarness(this)
        h.start(dest)
        val gate = CompletableDeferred<Unit>()
        h.reroute = { a, b, _ -> gate.await(); routeThrough(a, b) }
        h.goOffRoute(idle = false)
        assertEquals(1, h.rerouteCalls.size)
        // More off-route fixes and a network-available event while it's pending.
        repeat(5) { h.fix(O.at(70.0 + it * 10, 80.0), idle = false) }
        h.model.onNetworkAvailable(); runCurrent()
        assertEquals("no duplicate request", 1, h.rerouteCalls.size)
        // Rejoin the route (cancels the pending request) and leave again at once.
        repeat(3) { h.fix(O.at(120.0 + it * 15, 0.0), idle = false) }
        assertFalse(h.model.offRoute)
        h.goOffRoute(x0 = 150.0, idle = false)
        assertEquals(2, h.rerouteCalls.size)
        assertTrue("the new request's state isn't cleared by the cancelled one", h.model.rerouting)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, h.maxInFlight)
        assertFalse(h.model.offRoute)
    }

    @Test fun rerouteStartsInTheCarsDirectionOnlyWhenMoving() = runTest {
        val h = NavHarness(this)
        h.start(dest)
        h.reroute = { _, _, _ -> throw IOException("offline") }
        h.goOffRoute(speed = 12f, bearing = 45f)
        assertEquals(45f, h.rerouteCalls.last())

        val slow = NavHarness(this)
        slow.start(dest)
        slow.goOffRoute(speed = 0.5f, bearing = 200f)
        assertNull("a parked car's GPS course is noise", slow.rerouteCalls.last())
    }

    @Test fun drivingTheWrongWayTriggersAReroute() = runTest {
        val h = NavHarness(this)
        h.start(dest)
        h.reroute = { _, _, _ -> throw IOException("offline") } // keep the state observable
        repeat(3) { h.fix(O.at(15.0 + it * 15, 0.0), speed = 15f, bearing = EAST) }
        assertFalse(h.model.offRoute)
        assertTrue(h.rerouteCalls.isEmpty())
        // Turned round on the route line itself.
        repeat(3) { h.fix(O.at(35.0 - it * 12, 0.0), speed = 12f, bearing = WEST) }
        assertTrue(h.model.offRoute)
        assertTrue("rerouted from the car's actual direction", h.rerouteCalls.contains(WEST))
    }

    /** A fix with no usable heading (slow, or no GPS course) mustn't reset the wrong-way count. */
    @Test fun headinglessFixesDontHideWrongWayDriving() = runTest {
        val h = NavHarness(this)
        h.start(dest)
        h.reroute = { _, _, _ -> throw IOException("offline") }
        repeat(3) { h.fix(O.at(15.0 + it * 15, 0.0), speed = 15f, bearing = EAST) }
        for (k in 0 until 3) {
            h.fix(O.at(35.0 - k * 12, 0.0), speed = 12f, bearing = WEST)
            h.fix(O.at(33.0 - k * 12, 0.0), speed = 0f, bearing = 0f) // no trustworthy course
        }
        assertTrue(h.model.offRoute)
    }
}

// ---- M4: routing response parsing ------------------------------------------

class RoutingResponseTest {
    @Test fun osrmNoRouteAndNoSegmentAreGenuineNoRoute() {
        for (code in listOf("NoRoute", "NoSegment")) {
            try {
                Api.parseRoute("""{"code":"$code","message":"x"}"""); fail()
            } catch (e: NoRouteException) { /* expected */ }
        }
    }

    @Test fun otherOsrmErrorsAreServerProblems() {
        try {
            Api.parseRoute("""{"code":"TooBig"}"""); fail()
        } catch (e: RoutingUnavailableException) { /* expected */ }
    }

    @Test fun bearingParameter() {
        assertEquals("&bearings=90,60;", Api.bearingParam(90.2f))
        assertEquals("&bearings=0,60;", Api.bearingParam(359.7f))
        assertEquals("&bearings=270,60;", Api.bearingParam(-90f))
    }

    @Test fun navigationTimeLimitsAreShorterButNotTiny() {
        val nav = Api.RouteProfile.NAVIGATION
        val pre = Api.RouteProfile.PREVIEW
        assertTrue(nav.attemptMs < pre.attemptMs)
        assertTrue("both servers fit inside the reroute deadline", 2 * nav.attemptMs <= NavModel.REROUTE_DEADLINE_MS)
        assertTrue("still several seconds for a slow mobile link", nav.attemptMs >= 5_000)
    }
}
