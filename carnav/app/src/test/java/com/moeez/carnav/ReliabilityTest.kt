package com.moeez.carnav

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

// ---- Test helpers -----------------------------------------------------------

private class MemStore : KeyValueStore {
    val map = HashMap<String, String>()
    override fun getString(key: String) = map[key]
    override fun putString(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}

/** Point `m` metres east (+) / west (−) and `n` metres north of `p` (fine at city scale). */
private fun LatLon.offset(eastM: Double, northM: Double) =
    LatLon(lat + northM / 111_320.0, lon + eastM / (111_320.0 * Math.cos(Math.toRadians(lat))))

/** A simple route through [pts]: depart at the first point, arrive at the last. */
private fun routeOf(vararg pts: LatLon): Route {
    val tmp = Route(pts.toList(), 0.0, 0.0, emptyList())
    val steps = listOf(
        Step("depart", null, "Start Road", null, null, pts.first(), tmp.length, tmp.length / 12),
        Step("arrive", null, "", null, null, pts.last(), 0.0, 0.0)
    )
    return Route(pts.toList(), tmp.length, tmp.length / 12, steps) // ~43 km/h
}

private fun gps(p: LatLon, t: Long, acc: Float? = 5f, ageMs: Long = 0) =
    Fix(p, Fix.GPS, acc, ageMs, t)

/** A model wired to a fake router (straight line from→to) and a test clock. */
@OptIn(ExperimentalCoroutinesApi::class)
private class Harness(val scope: TestScope, val store: MemStore = MemStore()) {
    var now = 0L
    var online = true
    val routerCalls = ArrayList<Pair<LatLon, LatLon>>()
    var routeFor: (LatLon, LatLon) -> Route = { a, b -> routeOf(a, b) }
    val model = NavModel(
        scope, store,
        router = { a, b ->
            routerCalls += a to b
            if (!online) throw IOException("offline")
            routeFor(a, b)
        },
        nameLookup = { null },
        clock = { now },
        wallClock = { 1_700_000_000_000L }
    )

    /** Feed a fix taken "now", then advance the clock by 1 s. */
    fun fix(p: LatLon, acc: Float? = 5f, ageMs: Long = 0, provider: String = Fix.GPS): FixVerdict {
        val v = model.onFix(Fix(p, provider, acc, ageMs, now))
        now += 1000
        scope.advanceUntilIdle()
        return v
    }

    fun startTripTo(start: LatLon, dest: LatLon, name: String = "Dest") {
        fix(start)
        model.choose(Place(name, "", dest))
        scope.advanceUntilIdle()
        assertNotNull("route should be ready", model.route)
        model.start()
    }
}

private val START = LatLon(31.5000, 74.3000)

// ---- M1: fix filtering ------------------------------------------------------

class FixFilterTest {
    @Test fun normalFixIsAccepted() {
        assertEquals(FixVerdict.ACCEPT, FixFilter().check(gps(START, 0), navigating = true))
    }

    @Test fun inaccurateFixIsRejectedWhileNavigatingButOkForTheMap() {
        val f = FixFilter()
        assertEquals(FixVerdict.INACCURATE, f.check(gps(START, 0, acc = 120f), navigating = true))
        assertEquals(FixVerdict.ACCEPT, f.check(gps(START, 1000, acc = 120f), navigating = false))
        assertEquals(FixVerdict.INACCURATE, f.check(gps(START, 2000, acc = 400f), navigating = false))
    }

    @Test fun staleFixIsRejected() {
        assertEquals(FixVerdict.STALE, FixFilter().check(gps(START, 0, ageMs = 30_000), navigating = false))
    }

    @Test fun networkFixesNeverDriveNavigation() {
        val f = FixFilter()
        val net = Fix(START, "network", 30f, 0, 0)
        assertEquals(FixVerdict.NETWORK_WHILE_NAVIGATING, f.check(net, navigating = true))
        // Browsing: used only when GPS has gone quiet.
        f.check(gps(START, 10_000), navigating = false)
        assertEquals(FixVerdict.NETWORK_WHILE_GPS_OK, f.check(net.copy(timeMs = 12_000), navigating = false))
        assertEquals(FixVerdict.ACCEPT, f.check(net.copy(timeMs = 20_000), navigating = false))
    }

    @Test fun singleJumpIsRejectedButARealRelocationIsAcceptedAfterThreeFixes() {
        val f = FixFilter()
        assertEquals(FixVerdict.ACCEPT, f.check(gps(START, 0), true))
        val far = START.offset(0.0, 400.0) // 400 m in 1 s = 1440 km/h
        assertEquals(FixVerdict.JUMP, f.check(gps(far, 1000), true))
        assertEquals(FixVerdict.ACCEPT, f.check(gps(START.offset(10.0, 0.0), 2000), true)) // back to normal
        // GPS genuinely reappears elsewhere (e.g. after a tunnel): 3 agreeing fixes win.
        assertEquals(FixVerdict.JUMP, f.check(gps(far, 3000), true))
        assertEquals(FixVerdict.JUMP, f.check(gps(far.offset(5.0, 0.0), 4000), true))
        assertEquals(FixVerdict.ACCEPT, f.check(gps(far.offset(10.0, 0.0), 5000), true))
    }
}

// ---- M3: arrival on a divided road -----------------------------------------

/**
 * Divided road: drive east on the south carriageway, U-turn 600 m on, come back
 * west on the north carriageway (20 m away) to the destination. On the way out
 * the car passes 20 m from the destination — but 900 m from it along the route.
 */
private val DEST_B = START.offset(200.0, 20.0)
private val U_TURN_ROUTE = routeOf(
    START,
    START.offset(600.0, 0.0),   // end of the south carriageway
    START.offset(600.0, 20.0),  // U-turn
    DEST_B                       // back along the north carriageway
)

class ArrivalTest {
    @Test fun passingTheDestinationOnTheOtherCarriagewayIsNotArrival() {
        val tracker = RouteTracker(U_TURN_ROUTE)
        tracker.update(START, null)
        // Drive out at ~15 m/s; GPS noise pulls one fix 12 m toward the far side.
        for (k in 1..13) {
            val p = U_TURN_ROUTE.pointAt(k * 15.0)
            val noisy = if (k == 13) p.offset(0.0, 12.0) else p
            val pr = tracker.update(noisy, 1.0)
            assertFalse("arrived at ${k * 15} m", pr.arrived)
            assertTrue("matched to the wrong carriageway at ${k * 15} m", pr.remainingDistance > 500)
        }
        // The old rule fired here: within 25 m of the destination.
        assertTrue(Geo.distance(U_TURN_ROUTE.pointAt(195.0), DEST_B) < 25)
    }

    @Test fun arrivesOnlyAtTheEndAndOnlyOnce() = runTest {
        val h = Harness(this)
        h.routeFor = { _, _ -> U_TURN_ROUTE }
        h.startTripTo(START, DEST_B)
        assertNotNull(h.store.map[NavModel.KEY_TRIP])
        var announcements = 0
        var wasArrived = false
        var m = 0.0
        while (m <= U_TURN_ROUTE.length + 30) {
            h.fix(U_TURN_ROUTE.pointAt(m))
            if (h.model.arrived && !wasArrived) announcements++
            if (h.model.arrived) assertTrue("arrived early at $m m", m >= U_TURN_ROUTE.length - NavModel.OFF_ROUTE_M)
            wasArrived = h.model.arrived
            m += 15.0
        }
        assertEquals(1, announcements)
        // Latched: wandering off afterwards doesn't un-arrive or reroute.
        repeat(5) { h.fix(DEST_B.offset(0.0, 300.0)) }
        assertTrue(h.model.arrived)
        assertFalse(h.model.offRoute)
        assertNull("finished trips are not resumed", h.store.map[NavModel.KEY_TRIP])
    }
}

// ---- H3 + M1: off route, offline, recovery ---------------------------------

class OffRouteTest {
    private val dest = START.offset(3000.0, 0.0)

    @Test fun offlineDeviationHidesInstructionsAndRecoversWhenOnline() = runTest {
        val h = Harness(this)
        h.startTripTo(START, dest)
        repeat(3) { h.fix(START.offset(30.0 * (it + 1), 0.0)) }
        assertFalse(h.model.offRoute)

        // Internet drops, then the driver turns off the route (north).
        h.online = false
        val callsBefore = h.routerCalls.size
        h.fix(START.offset(120.0, 60.0)); h.fix(START.offset(120.0, 90.0))
        assertFalse("2 fixes are not enough to call it off-route", h.model.offRoute)
        h.fix(START.offset(120.0, 120.0))
        assertTrue("confirmed off route after 3 fixes", h.model.offRoute)
        assertTrue("reroute attempted at once", h.routerCalls.size == callsBefore + 1)
        assertTrue("failure is shown as waiting for connection", h.model.rerouteFailed)
        assertFalse(h.model.rerouting)

        // Backing off: no hammering the server every second.
        val afterFirst = h.routerCalls.size
        h.fix(START.offset(120.0, 150.0)); h.fix(START.offset(120.0, 180.0))
        assertEquals(afterFirst, h.routerCalls.size)
        h.now += 5000
        h.fix(START.offset(120.0, 210.0))
        assertEquals("retried after the back-off", afterFirst + 1, h.routerCalls.size)
        assertTrue(h.model.offRoute)

        // Connection returns: retried immediately, new route from where the car is.
        h.online = true
        h.model.onNetworkAvailable()
        advanceUntilIdle()
        assertFalse(h.model.offRoute)
        assertFalse(h.model.rerouteFailed)
        val newStart = h.model.route!!.points.first()
        assertTrue("new route starts at the car", Geo.distance(newStart, START.offset(120.0, 210.0)) < 5)
        h.fix(START.offset(130.0, 210.0))
        assertFalse(h.model.offRoute)
        assertTrue(h.model.progress!!.offRoute < 15)
    }

    @Test fun inaccurateAndGlitchFixesNeverTriggerOffRoute() = runTest {
        val h = Harness(this)
        h.startTripTo(START, dest)
        h.fix(START.offset(15.0, 0.0))
        val along = h.model.progress!!.along
        // A single GPS glitch 1 s after a good fix, with a good accuracy figure:
        // 400 m in 1 s is impossible, so it's rejected as a jump.
        assertEquals(FixVerdict.JUMP, h.fix(START.offset(30.0, 400.0)))
        // Very inaccurate fixes far away: rejected outright.
        repeat(5) { assertEquals(FixVerdict.INACCURATE, h.fix(START.offset(30.0, 300.0), acc = 300f)) }
        // A stale fix: rejected.
        assertEquals(FixVerdict.STALE, h.fix(START.offset(30.0, 300.0), ageMs = 60_000))
        // A network fix: rejected while navigating.
        assertEquals(FixVerdict.NETWORK_WHILE_NAVIGATING, h.fix(START.offset(30.0, 300.0), provider = "network"))
        assertFalse(h.model.offRoute)
        assertEquals(along, h.model.progress!!.along, 0.001)
        assertEquals(1, h.routerCalls.size) // only the original route
        // Normal fixes carry on as before.
        h.fix(START.offset(45.0, 0.0))
        assertTrue(h.model.progress!!.along > along)
    }

    @Test fun twoBadFixesThenBackOnRouteIsNotOffRoute() = runTest {
        val h = Harness(this)
        h.startTripTo(START, dest)
        h.fix(START.offset(20.0, 0.0))
        h.fix(START.offset(35.0, 70.0)); h.fix(START.offset(50.0, 70.0))
        h.fix(START.offset(65.0, 0.0))
        assertFalse(h.model.offRoute)
        assertEquals(1, h.routerCalls.size)
    }

    @Test fun rejoiningThePlannedRouteRestoresItWithoutInternet() = runTest {
        val h = Harness(this)
        h.startTripTo(START, dest)
        h.online = false
        h.fix(START.offset(20.0, 0.0))
        repeat(3) { h.fix(START.offset(40.0 + it * 10, 80.0)) }
        assertTrue(h.model.offRoute)
        repeat(3) { h.fix(START.offset(120.0 + it * 15, 0.0)) }
        assertFalse("back on the planned route", h.model.offRoute)
        assertFalse(h.model.rerouteFailed)
    }
}

// ---- M7: trip survives the app being killed --------------------------------

class TripRecoveryTest {
    private val dest = START.offset(3000.0, 0.0)

    @Test fun killedTripResumesWithAFreshRouteFromTheCurrentPosition() = runTest {
        val store = MemStore()
        Harness(this, store).startTripTo(START, dest, name = "Emporium Mall")
        assertNotNull(store.map[NavModel.KEY_TRIP])

        // "Process death": a brand-new model on the same storage.
        val h = Harness(this, store)
        assertTrue(h.model.restoreTrip())
        assertEquals(Mode.NAVIGATE, h.model.mode)
        assertTrue(h.model.resuming)
        assertNull(h.model.route)
        assertEquals("Emporium Mall", h.model.destination!!.name)

        val here = START.offset(800.0, 250.0) // the car moved while the app was dead
        h.fix(here)
        assertEquals("routed from the current position", here, h.routerCalls.single().first)
        assertEquals(dest, h.routerCalls.single().second)
        assertFalse(h.model.resuming)
        assertNotNull(h.model.route)
        assertTrue(h.model.progress!!.offRoute < 5)
    }

    @Test fun offlineResumeWaitsThenRecovers() = runTest {
        val store = MemStore()
        Harness(this, store).startTripTo(START, dest)
        val h = Harness(this, store)
        h.online = false
        h.model.restoreTrip()
        h.fix(START)
        assertTrue(h.model.resuming)
        assertTrue(h.model.rerouteFailed)
        h.online = true
        h.model.onNetworkAvailable()
        advanceUntilIdle()
        assertFalse(h.model.resuming)
        assertNotNull(h.model.route)
    }

    @Test fun endingTheTripClearsIt() = runTest {
        val store = MemStore()
        val h = Harness(this, store)
        h.startTripTo(START, dest)
        h.model.end()
        assertNull(store.map[NavModel.KEY_TRIP])
        assertFalse(Harness(this, store).model.restoreTrip())
    }

    @Test fun corruptOrOldTripsAreDiscarded() = runTest {
        for (bad in listOf("{not json", "{}", """{"n":"X","lat":"abc","lon":74.3,"at":1700000000000}""",
            """{"n":"X","d":"","lat":31.5,"lon":74.3,"at":1600000000000}""")) { // 3+ years old
            val store = MemStore().apply { map[NavModel.KEY_TRIP] = bad }
            val h = Harness(this, store)
            assertFalse(bad, h.model.restoreTrip())
            assertEquals(Mode.BROWSE, h.model.mode)
            assertNull("bad state is cleared: $bad", store.map[NavModel.KEY_TRIP])
        }
    }
}
