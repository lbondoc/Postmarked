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
