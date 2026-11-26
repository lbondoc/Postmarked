package io.github.lbondoc.postmarked.domain.geo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** A place-search result usable as a trip region: [geometry] is a GeoJSON Polygon or MultiPolygon. */
data class Place(val label: String, val geometry: JsonObject)

/**
 * Reads a Nominatim (OpenStreetMap) search response. Places mapped as an outline keep it;
 * places mapped as a single point (many towns) fall back to the rectangle Nominatim reports
 * around them. Entries with neither are dropped. Throws if [json] is not a JSON array.
 */
fun placesFromNominatim(json: String): List<Place> =
    Json.parseToJsonElement(json).jsonArray.mapNotNull { (it as? JsonObject)?.toPlace() }

/**
 * A short name for where a Nominatim reverse lookup landed: the village, town or city, else the
 * area's own name (a forest, a park), else the county. Null when it found nothing.
 */
fun nameFromReverse(json: String): String? {
    val found = Json.parseToJsonElement(json) as? JsonObject ?: return null
    val address = found["address"] as? JsonObject
    fun text(o: JsonObject?, key: String) = (o?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    return listOf("village", "town", "city", "hamlet", "suburb").firstNotNullOfOrNull { text(address, it) }
        ?: text(found, "name")
        ?: text(address, "county")
}

private fun JsonObject.toPlace(): Place? {
    // `as?` is a safe cast: null instead of a crash when the server sends something unexpected.
    val label = (this["display_name"] as? JsonPrimitive)?.contentOrNull ?: return null
    val outline = this["geojson"] as? JsonObject
    val type = (outline?.get("type") as? JsonPrimitive)?.contentOrNull
    if (outline != null && (type == "Polygon" || type == "MultiPolygon")) return Place(label, outline)

    val box = (this["boundingbox"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }
    if (box?.size != 4) return null
    val (south, north, west, east) = box
    return Place(label, rectangle(south, north, west, east))
}

private fun rectangle(south: Double, north: Double, west: Double, east: Double) = buildJsonObject {
    put("type", "Polygon")
    putJsonArray("coordinates") {
        addJsonArray {
            // GeoJSON order is longitude first, and a ring ends where it began.
            for ((lng, lat) in listOf(west to south, east to south, east to north, west to north, west to south)) {
                addJsonArray { add(lng); add(lat) }
            }
        }
    }
}

/**
 * The middle of the place's outline (the centre of its bounding box), for using a searched place as a single
 * point: a trip's start or end, or where a photo was taken. Null when the geometry has no coordinates.
 */
fun Place.centre(): LatLng? {
    val lngs = mutableListOf<Double>()
    val lats = mutableListOf<Double>()
    fun walk(e: kotlinx.serialization.json.JsonElement) {
        val a = e as? JsonArray ?: return
        val x = (a.getOrNull(0) as? JsonPrimitive)?.doubleOrNull
        val y = (a.getOrNull(1) as? JsonPrimitive)?.doubleOrNull
        if (x != null && y != null) { lngs += x; lats += y } else a.forEach(::walk)
    }
    geometry["coordinates"]?.let(::walk)
    if (lats.isEmpty()) return null
    return LatLng((lats.min() + lats.max()) / 2, (lngs.min() + lngs.max()) / 2)
}
