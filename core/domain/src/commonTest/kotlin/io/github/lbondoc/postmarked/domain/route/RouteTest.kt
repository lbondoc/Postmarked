package io.github.lbondoc.postmarked.domain.route

import io.github.lbondoc.postmarked.domain.geo.LatLng
import io.github.lbondoc.postmarked.domain.geo.haversineMeters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate

class RouteTest {
    private val day1 = LocalDate(2026, 10, 3)
    private val day2 = LocalDate(2026, 10, 4)
    private val hour = 3_600_000L

    // Places along Highway 1, south from San Francisco.
    private val sf = LatLng(37.7749, -122.4194)
    private val santaCruz = LatLng(36.9741, -122.0308)
    private val monterey = LatLng(36.6002, -121.8947)
    private val bigSur = LatLng(36.2704, -121.8081)
    private val newYork = LatLng(40.7128, -74.0060)

    private var nextId = 0
    private fun point(at: LatLng, hours: Double, day: LocalDate = day1) =
        RoutePoint("p${nextId++}", (hours * hour).toLong(), day, at)

    /** A spot a few metres from [at], the way several shots at one place land. */
    private fun near(at: LatLng, metres: Double) = LatLng(at.lat + metres / 111_000, at.lng)

    @Test
    fun photosInARowAtOnePlaceMakeOneStop() {
        val plan = planRoute(listOf(point(sf, 9.0), point(near(sf, 100.0), 9.2), point(near(sf, 300.0), 9.5)))
        assertEquals(1, plan.stops.size)
        assertEquals(3, plan.stops[0].ids.size)
        assertEquals((9.0 * hour).toLong(), plan.stops[0].arrivedMs)
        assertEquals((9.5 * hour).toLong(), plan.stops[0].leftMs)
        assertTrue(plan.legs.isEmpty())
    }

    @Test
    fun orderComesFromTimeNotFromWhoUploadedFirst() {
        // Two friends' photos arrive interleaved and out of order.
        val plan = planRoute(listOf(point(monterey, 14.0), point(sf, 9.0), point(bigSur, 17.0), point(santaCruz, 11.0)))
        assertEquals(listOf(sf, santaCruz, monterey, bigSur), plan.stops.map { it.at })
        assertEquals(3, plan.legs.size)
        assertTrue(plan.legs.all { it.driven })
    }

    @Test
    fun aStopIsCentredOnItsPhotos() {
        val plan = planRoute(listOf(point(sf, 9.0), point(near(sf, 400.0), 9.1)))
        assertEquals(200.0, haversineMeters(sf, plan.stops.single().at), 1.0)
    }

    @Test
    fun comingBackToAPlaceLaterIsANewStop() {
        val plan = planRoute(listOf(point(monterey, 9.0), point(bigSur, 12.0), point(monterey, 16.0)))
        assertEquals(3, plan.stops.size)
    }

    @Test
    fun aGpsGlitchBetweenTwoAgreeingPhotosIsDropped() {
        // A phone reusing an old fix: one photo "in New York" ten minutes into a California afternoon.
        val glitch = point(newYork, 9.1)
        val plan = planRoute(listOf(point(sf, 9.0), glitch, point(near(sf, 50.0), 9.2)))
        assertEquals(1, plan.stops.size)
        assertFalse(glitch.id in plan.stops.single().ids)
    }

    @Test
    fun theFirstAndLastPhotosAreNeverDroppedAsGlitches() {
        val plan = planRoute(listOf(point(newYork, 0.0), point(sf, 0.2)))
        assertEquals(2, plan.stops.size)
    }

    @Test
    fun aFlightIsNotADrive() {
        // Fly New York to San Francisco in 6 hours, then drive south.
        val plan = planRoute(listOf(point(newYork, 0.0), point(sf, 6.0), point(santaCruz, 8.0), point(monterey, 9.5)))
        assertEquals(listOf(false, true, true), plan.legs.map { it.driven })
        assertEquals(listOf(listOf(sf, santaCruz, monterey)), plan.drivenStretches().map { s -> s.map { it.at } })
    }

    @Test
    fun aLongDayOfDrivingIsStillADrive() {
        // About 560 km in 7 hours.
        val la = LatLng(34.0522, -118.2437)
        assertTrue(planRoute(listOf(point(sf, 8.0), point(la, 15.0))).legs.single().driven)
    }

    @Test
    fun aLegCountsTowardTheDayItArrives() {
        val plan = planRoute(listOf(point(monterey, 20.0, day1), point(bigSur, 33.0, day2)))
        assertEquals(day2, plan.legs.single().day)
    }

    @Test
    fun aStretchBreaksAtEachJump() {
        val plan = planRoute(
            listOf(point(sf, 0.0), point(santaCruz, 1.5), point(newYork, 7.0), point(near(newYork, 5_000.0), 7.5)),
        )
        assertEquals(listOf(true, false, true), plan.legs.map { it.driven })
        assertEquals(listOf(2, 2), plan.drivenStretches().map { it.size })
    }

    @Test
    fun noPhotosMeansNoRoute() {
        val plan = planRoute(emptyList())
        assertTrue(plan.stops.isEmpty() && plan.legs.isEmpty() && plan.drivenStretches().isEmpty())
    }

    @Test
    fun longStretchesAreSplitIntoOverlappingPieces() {
        assertEquals(listOf(listOf(1, 2, 3), listOf(3, 4, 5), listOf(5, 6)), pieces((1..6).toList(), max = 3))
        assertEquals(listOf(listOf(1, 2, 3)), pieces(listOf(1, 2, 3), max = 3))
        assertEquals(emptyList(), pieces(listOf(1), max = 3), "one stop needs no route")
        assertEquals(listOf(listOf(1, 2), listOf(2, 3)), pieces(listOf(1, 2, 3), max = 2))
        assertFailsWith<IllegalArgumentException> { pieces(listOf(1, 2), max = 1) }
    }

    @Test
    fun dayTotalsCountDrivenLegsAlongTheirPaths() {
        val plan = planRoute(
            listOf(point(sf, 9.0, day1), point(santaCruz, 11.0, day1), point(monterey, 33.0, day2), point(newYork, 40.0, day2)),
        )
        val (first, second, flight) = plan.legs
        // The first leg detours via an extra point, so it is longer than the straight line.
        val viaPoint = LatLng(37.3, -122.4)
        val routed = listOf(
            RoutedLeg(first, listOf(sf, viaPoint, santaCruz), seconds = 5_400.0, snapped = true),
            RoutedLeg(second, listOf(santaCruz, monterey), seconds = null, snapped = false),
            RoutedLeg(flight, listOf(monterey, newYork), seconds = null, snapped = false),
        )
        val days = summarizeDays(plan.stops, routed)
        assertEquals(listOf(day1, day2), days.map { it.day })
        assertEquals(haversineMeters(sf, viaPoint) + haversineMeters(viaPoint, santaCruz), days[0].meters, 1e-6)
        assertEquals(5_400.0, days[0].seconds)
        assertFalse(days[0].approximate)
        assertEquals(haversineMeters(santaCruz, monterey), days[1].meters, 1e-6, "the flight is not counted")
        assertTrue(days[1].approximate)
    }

    @Test
    fun aDayWithPhotosButNoDrivingShowsZero() {
        val plan = planRoute(listOf(point(sf, 9.0, day1), point(near(sf, 50.0), 30.0, day2)))
        assertEquals(listOf(0.0), summarizeDays(plan.stops, emptyList()).map { it.meters })
    }

    @Test
    fun decodesValhallaPolylines() {
        // Google's documented example, written at precision 5.
        assertEquals(
            listOf(LatLng(38.5, -120.2), LatLng(40.7, -120.95), LatLng(43.252, -126.453)),
            decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@", precision = 5),
        )
        // The same three points at precision 6, as Valhalla sends them.
        assertEquals(
            listOf(LatLng(38.5, -120.2), LatLng(40.7, -120.95), LatLng(43.252, -126.453)),
            decodePolyline("_izlhA~rlgdF_{geC~ywl@_kwzCn`{nI"),
        )
        assertEquals(emptyList(), decodePolyline(""))
    }

    @Test
    fun aCutOffPolylineIsRejected() {
        assertFailsWith<IllegalArgumentException> { decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq") }
    }
}
