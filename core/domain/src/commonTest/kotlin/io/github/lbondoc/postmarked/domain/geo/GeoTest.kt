package io.github.lbondoc.postmarked.domain.geo

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GeoTest {
    private val sf = LatLng(37.7749, -122.4194)
    private val la = LatLng(34.0522, -118.2437)

    @Test
    fun sameSpotIsZero() = assertEquals(0.0, haversineMeters(sf, sf))

    @Test
    fun oneDegreeOfLatitudeIsAbout111Km() =
        assertEquals(111_195.0, haversineMeters(LatLng(0.0, 0.0), LatLng(1.0, 0.0)), 1.0)

    @Test
    fun sanFranciscoToLosAngelesMatchesPublishedDistance() =
        assertEquals(559_120.0, haversineMeters(sf, la), 1_000.0)

    @Test
    fun distanceIsSymmetric() =
        assertEquals(haversineMeters(sf, la), haversineMeters(la, sf), 1e-6)

    @Test
    fun crossingTheDateLineTakesTheShortWay() =
        assertEquals(222_390.0, haversineMeters(LatLng(0.0, 179.0), LatLng(0.0, -179.0)), 1.0)

    @Test
    fun antipodalPointsAreHalfTheGlobe() =
        assertEquals(PI * EARTH_RADIUS_M, haversineMeters(LatLng(0.0, 0.0), LatLng(0.0, 180.0)), 1.0)

    @Test
    fun pathLengthAddsUpTheLegs() {
        val a = LatLng(0.0, 0.0)
        val b = LatLng(1.0, 0.0)
        val c = LatLng(1.0, 1.0)
        assertEquals(haversineMeters(a, b) + haversineMeters(b, c), pathLengthMeters(listOf(a, b, c)), 1e-6)
    }

    @Test
    fun pathWithFewerThanTwoPointsHasNoLength() {
        assertEquals(0.0, pathLengthMeters(emptyList()))
        assertEquals(0.0, pathLengthMeters(listOf(sf)))
    }

    @Test
    fun gpsOrNullRejectsValuesThatAreNotRealFixes() {
        assertNull(gpsOrNull(0.0, 0.0))
        assertNull(gpsOrNull(Double.NaN, 10.0))
        assertNull(gpsOrNull(91.0, 10.0))
        assertNull(gpsOrNull(10.0, -181.0))
        assertEquals(LatLng(0.0, 10.0), gpsOrNull(0.0, 10.0))
    }

    @Test
    fun parsesAndroidVideoLocation() {
        assertEquals(LatLng(37.4219, -122.084), parseIso6709("+37.4219-122.0840/"))
        assertEquals(LatLng(27.5916, 86.564), parseIso6709("+27.5916+086.5640+8850CRSWGS_84/"))
        assertEquals(LatLng(-33.8568, 151.2153), parseIso6709("-33.8568+151.2153/"))
    }

    @Test
    fun rejectsMalformedOrEmptyVideoLocation() {
        assertNull(parseIso6709(""))
        assertNull(parseIso6709("37.4219,-122.0840"))
        assertNull(parseIso6709("+00.0000+000.0000/"))
        assertNull(parseIso6709("+95.0000+000.0000/"))
    }
}
