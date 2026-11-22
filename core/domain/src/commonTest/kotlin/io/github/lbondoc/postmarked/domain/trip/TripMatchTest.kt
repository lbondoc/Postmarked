package io.github.lbondoc.postmarked.domain.trip

import io.github.lbondoc.postmarked.domain.geo.LatLng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class TripMatchTest {
    private fun shape(json: String) = Json.parseToJsonElement(json).jsonObject

    // A 1° x 1° box west of Monterey, with a 0.2° hole in the middle.
    private val boxWithHole = shape(
        """{"type": "Polygon", "coordinates": [
             [[-122, 36], [-121, 36], [-121, 37], [-122, 37], [-122, 36]],
             [[-121.6, 36.4], [-121.4, 36.4], [-121.4, 36.6], [-121.6, 36.6], [-121.6, 36.4]]]}"""
    )
    // Two small squares far apart, the way a trip through two parks would look.
    private val twoParks = shape(
        """{"type": "MultiPolygon", "coordinates": [
             [[[-116, 34], [-115.9, 34], [-115.9, 34.1], [-116, 34.1], [-116, 34]]],
             [[[-112.2, 36], [-112.1, 36], [-112.1, 36.1], [-112.2, 36.1], [-112.2, 36]]]]}"""
    )
    // Straddles the date line, split in two at ±180 the way OpenStreetMap stores it.
    private val acrossDateLine = shape(
        """{"type": "MultiPolygon", "coordinates": [
             [[[179, 51], [180, 51], [180, 52], [179, 52], [179, 51]]],
             [[[-180, 51], [-179, 51], [-179, 52], [-180, 52], [-180, 51]]]]}"""
    )

    private val area = TripArea(listOf(boxWithHole, twoParks))

    @Test
    fun insideARegion() = assertTrue(area.covers(LatLng(36.2, -121.8)))

    @Test
    fun insideTheSecondPartOfAMultiPolygon() = assertTrue(area.covers(LatLng(36.05, -112.15)))

    @Test
    fun aHoleIsNotInside() =
        assertFalse(area.covers(LatLng(36.5, -121.5), toleranceMeters = 0.0))

    @Test
    fun withinToleranceOutsideTheEdgeCounts() {
        // 0.2° of latitude south of the box is about 22 km.
        assertTrue(area.covers(LatLng(35.8, -121.5)))
        assertFalse(area.covers(LatLng(35.8, -121.5), toleranceMeters = 20_000.0))
    }

    @Test
    fun nearACornerMeasuresToTheCorner() {
        // 0.15° south and 0.15° west of the box's south-west corner: about 21 km diagonally.
        assertTrue(area.covers(LatLng(35.85, -122.15)))
        assertFalse(area.covers(LatLng(35.85, -122.15), toleranceMeters = 19_000.0))
    }

    @Test
    fun farAwayIsOutside() = assertFalse(area.covers(LatLng(40.7, -74.0)))

    @Test
    fun theDateLineIsHandled() {
        val aleutians = TripArea(listOf(acrossDateLine))
        assertTrue(aleutians.covers(LatLng(51.5, 179.9), toleranceMeters = 0.0))
        assertTrue(aleutians.covers(LatLng(51.5, -179.9), toleranceMeters = 0.0))
        assertFalse(aleutians.covers(LatLng(51.5, 170.0)))
    }

    @Test
    fun distanceIsMeasuredTheShortWayAcrossTheDateLine() {
        // Only the western half (179° to 180°); the point is 0.1° east of it, about 7 km,
        // even though its longitude number is nearly 360° away.
        val westernHalf = TripArea(listOf(shape(
            """{"type": "Polygon", "coordinates": [[[179, 51], [180, 51], [180, 52], [179, 52], [179, 51]]]}"""
        )))
        assertTrue(westernHalf.covers(LatLng(51.5, -179.9)))
        assertFalse(westernHalf.covers(LatLng(51.5, -179.9), toleranceMeters = 5_000.0))
    }

    @Test
    fun onlyPolygonsAreAccepted() {
        assertFailsWith<IllegalArgumentException> {
            TripArea(listOf(shape("""{"type": "Point", "coordinates": [-121, 36]}""")))
        }
    }

    private val start = LocalDate(2026, 10, 3)
    private val end = LocalDate(2026, 10, 5)
    private val inBox = LatLng(36.2, -121.8)
    private val newYork = LatLng(40.7, -74.0)

    @Test
    fun matchesNeedBothDatesAndPlace() {
        assertEquals(TripMatch.Both, matchTrip(LocalDate(2026, 10, 4), inBox, start, end, area))
        assertEquals(TripMatch.DatesOnly, matchTrip(LocalDate(2026, 10, 4), newYork, start, end, area))
        assertEquals(TripMatch.LocationOnly, matchTrip(LocalDate(2026, 10, 6), inBox, start, end, area))
        assertEquals(TripMatch.Neither, matchTrip(LocalDate(2026, 9, 30), newYork, start, end, area))
    }

    @Test
    fun firstAndLastDaysAreIncluded() {
        assertEquals(TripMatch.Both, matchTrip(start, inBox, start, end, area))
        assertEquals(TripMatch.Both, matchTrip(end, inBox, start, end, area))
    }

    @Test
    fun noLocationCanOnlyMatchOnDates() {
        assertEquals(TripMatch.DatesOnly, matchTrip(LocalDate(2026, 10, 4), null, start, end, area))
        assertEquals(TripMatch.Neither, matchTrip(LocalDate(2026, 10, 9), null, start, end, area))
    }
}
