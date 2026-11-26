package io.github.lbondoc.postmarked.ui

import io.github.lbondoc.postmarked.data.EditableTrip
import io.github.lbondoc.postmarked.data.TripPoint
import io.github.lbondoc.postmarked.domain.geo.Place
import kotlinx.datetime.LocalDate

/** The New trip / Edit trip form's values (SPEC §9.1). [startPoint] and [endPoint] are optional. */
data class TripForm(
    val name: String = "",
    val start: LocalDate? = null,
    val end: LocalDate? = null,
    val regions: List<Place> = emptyList(),
    val startPoint: TripPoint? = null,
    val endPoint: TripPoint? = null,
) {
    /** A trip needs a name, dates in order and at least one region. */
    val canSave: Boolean
        get() = name.isNotBlank() && start != null && end != null && end >= start && regions.isNotEmpty()
}

/**
 * What saving [form] should send when it was loaded as [original]: the whole form (regions are replaced whole),
 * or null when nothing changed, so Save sends nothing. A name differing only in outer spaces is unchanged.
 */
fun tripEdit(original: TripForm, form: TripForm): TripForm? {
    val trimmed = form.copy(name = form.name.trim())
    return trimmed.takeIf { it != original.copy(name = original.name.trim()) }
}

/** The form filled in with a stored trip, for Edit trip. */
fun EditableTrip.toForm() = TripForm(name, startDate, endDate, regions.map { Place(it.label, it.geojson) }, startPoint, endPoint)
