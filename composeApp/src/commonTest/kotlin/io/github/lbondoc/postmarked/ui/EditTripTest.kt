package io.github.lbondoc.postmarked.ui

import io.github.lbondoc.postmarked.data.EditableTrip
import io.github.lbondoc.postmarked.data.TripPoint
import io.github.lbondoc.postmarked.domain.geo.Place
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class EditTripTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun shape(n: Int) = Json.parseToJsonElement(
        """{"type": "MultiPolygon", "coordinates": [[[[$n, 0], [${n + 1}, 0], [${n + 1}, 1], [$n, 0]]]]}""",
    ) as JsonObject

    // As Trips.forEditing reads it from the database.
    private val stored = json.decodeFromString<EditableTrip>(
        """{"name": "Coast run", "start_date": "2026-09-12", "end_date": "2026-09-15",
            "start_label": "San Francisco", "end_label": null,
            "start_geojson": {"type": "Point", "coordinates": [-122.42, 37.77]}, "end_geojson": null,
            "trip_regions": [{"label": "Big Sur", "geojson": ${shape(0)}}, {"label": "Monterey", "geojson": ${shape(5)}}]}""",
    )

    @Test
    fun theFormStartsWithTheTripsValues() {
        val form = stored.toForm()
        assertEquals("Coast run", form.name)
        assertEquals(LocalDate(2026, 9, 12), form.start)
        assertEquals(LocalDate(2026, 9, 15), form.end)
        assertEquals(listOf(Place("Big Sur", shape(0)), Place("Monterey", shape(5))), form.regions)
        assertEquals(TripPoint("San Francisco", 37.77, -122.42), form.startPoint)
        assertNull(form.endPoint)
        assertTrue(form.canSave)
    }

    @Test
    fun savingWithNoChangesSendsNothing() {
        val form = stored.toForm()
        assertNull(tripEdit(form, form))
        assertNull(tripEdit(form, form.copy(name = "  Coast run ")), "outer spaces aren't a change")
        assertEquals("Coast run 2", tripEdit(form, form.copy(name = " Coast run 2"))?.name, "a real rename is sent, trimmed")
    }

    @Test
    fun anEndDateBeforeTheStartDisablesSave() {
        val form = stored.toForm()
        assertFalse(form.copy(end = LocalDate(2026, 9, 11)).canSave)
        assertTrue(form.copy(end = LocalDate(2026, 9, 12)).canSave, "a one-day trip is fine")
        assertFalse(form.copy(name = "  ").canSave)
        assertFalse(form.copy(regions = emptyList()).canSave)
        assertFalse(form.copy(start = null).canSave)
    }

    @Test
    fun aChangedRegionListIsSentWhole() {
        val form = stored.toForm()
        val sent = tripEdit(form, form.copy(regions = form.regions.drop(1) + Place("Carmel", shape(9))))
        assertEquals(listOf(Place("Monterey", shape(5)), Place("Carmel", shape(9))), sent?.regions)
        assertEquals(form.name, sent?.name, "the rest of the form goes along unchanged")
        assertEquals(form.startPoint, sent?.startPoint)
    }
}
