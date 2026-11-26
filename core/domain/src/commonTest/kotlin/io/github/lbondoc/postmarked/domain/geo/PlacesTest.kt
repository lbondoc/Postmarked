package io.github.lbondoc.postmarked.domain.geo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlacesTest {
    // Trimmed from a real response to: /search?q=Joshua+Tree&format=jsonv2&polygon_geojson=1
    private val response = """
        [
          {"place_id": 1, "name": "Joshua Tree", "category": "boundary", "type": "census",
           "display_name": "Joshua Tree, San Bernardino County, California, United States",
           "boundingbox": ["34.0759380", "34.1716260", "-116.3704200", "-116.2558110"],
           "geojson": {"type": "Polygon", "coordinates": [[[-116.37, 34.08], [-116.26, 34.08], [-116.26, 34.17], [-116.37, 34.08]]]}},
          {"place_id": 2, "name": "Joshua Tree", "category": "place", "type": "town",
           "display_name": "Joshua Tree, San Bernardino County, California, 92295, United States",
           "boundingbox": ["34.0951028", "34.1751028", "-116.3543311", "-116.2743311"],
           "geojson": {"type": "Point", "coordinates": [-116.3143311, 34.1351028]}},
          {"place_id": 3, "display_name": "A road", "geojson": {"type": "LineString", "coordinates": [[0, 0], [1, 1]]}},
          {"place_id": 4, "geojson": {"type": "Polygon", "coordinates": []}},
          "not an object"
        ]
    """

    @Test
    fun keepsOutlinesAndTurnsPointsIntoTheirBoundingRectangle() {
        val places = placesFromNominatim(response)

        assertEquals(2, places.size, "entries without a name or a usable area are dropped")
        assertEquals("Joshua Tree, San Bernardino County, California, United States", places[0].label)
        assertEquals(
            """{"type":"Polygon","coordinates":[[[-116.37,34.08],[-116.26,34.08],[-116.26,34.17],[-116.37,34.08]]]}""",
            places[0].geometry.toString(),
        )
        // boundingbox is [south, north, west, east]; GeoJSON wants [lng, lat] and a closed ring.
        assertEquals(
            """{"type":"Polygon","coordinates":[[[-116.3543311,34.0951028],[-116.2743311,34.0951028],""" +
                """[-116.2743311,34.1751028],[-116.3543311,34.1751028],[-116.3543311,34.0951028]]]}""",
            places[1].geometry.toString(),
        )
    }

    @Test
    fun noResultsIsAnEmptyList() = assertEquals(emptyList(), placesFromNominatim("[]"))

    @Test
    fun anErrorBodyThrowsInsteadOfLookingLikeNoResults() {
        assertFailsWith<IllegalArgumentException> { placesFromNominatim("""{"error": "rate limited"}""") }
    }

    @Test
    fun reverseLookupNamesTheTownOrTheArea() {
        assertEquals("Carmel-by-the-Sea", nameFromReverse(
            """{"name": "Carmel Point", "address": {"suburb": "Carmel Point", "town": "Carmel-by-the-Sea", "county": "Monterey County"}}"""
        ))
        // Out in the wilds there's no town, so the area's own name.
        assertEquals("Los Padres National Forest", nameFromReverse(
            """{"name": "Los Padres National Forest", "address": {"county": "Monterey County", "state": "California"}}"""
        ))
        assertEquals("Monterey County", nameFromReverse("""{"name": "", "address": {"county": "Monterey County"}}"""))
        assertEquals(null, nameFromReverse("""{"error": "Unable to geocode"}"""))
    }

    @Test
    fun aPlacesCentreIsTheMiddleOfItsOutline() {
        val square = placesFromNominatim(response)[0]
        assertEquals(LatLng((34.08 + 34.17) / 2, (-116.37 + -116.26) / 2), square.centre())
        val multi = Place("Two islands", kotlinx.serialization.json.Json.parseToJsonElement(
            """{"type": "MultiPolygon", "coordinates": [[[[0, 0], [1, 0], [1, 1], [0, 0]]], [[[4, 2], [5, 2], [5, 3], [4, 2]]]]}""",
        ) as kotlinx.serialization.json.JsonObject)
        assertEquals(LatLng(1.5, 2.5), multi.centre())
        assertEquals(null, Place("Empty", kotlinx.serialization.json.buildJsonObject { }).centre())
    }
}
