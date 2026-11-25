package io.github.lbondoc.postmarked.domain.summary

/** One slide of a trip carousel (SPEC §8.3). [Photos] holds indices into the photos given to [carouselPlan]. */
sealed interface CarouselSlide {
    data object Cover : CarouselSlide
    data class Photos(val photos: List<Int>) : CarouselSlide
    data object Route : CarouselSlide
    data object Closing : CarouselSlide
}

/** Instagram's limit on slides in one carousel post. */
const val MAX_SLIDES = 20

/** A photo for a carousel: when it was taken and which trip day it belongs to. */
data class CarouselPhoto(val takenAtMs: Long, val day: Int)

/**
 * The slides of a trip carousel: a cover, the photos in time order [perSlide] to a slide, a route slide when
 * [hasRoute], and a closing slide, never more than [MAX_SLIDES]. Photo slides start a new slide at each day when
 * that fits; if not, days share slides; if even that is too many, photos are picked evenly across the trip.
 */
fun carouselPlan(photos: List<CarouselPhoto>, perSlide: Int, hasRoute: Boolean): List<CarouselSlide> {
    require(perSlide >= 1)
    val room = MAX_SLIDES - 2 - (if (hasRoute) 1 else 0)
    val order = photos.indices.sortedWith(compareBy({ photos[it].takenAtMs }, { it }))
    val byDay = order.fold(mutableListOf<MutableList<Int>>()) { days, i ->
        if (days.lastOrNull()?.let { photos[it.last()].day } != photos[i].day) days += mutableListOf<Int>()
        days.last() += i
        days
    }.flatMap { it.chunked(perSlide) }
    val chunks = when {
        byDay.size <= room -> byDay
        order.chunked(perSlide).size <= room -> order.chunked(perSlide)
        else -> {
            val keep = room * perSlide
            List(keep) { k -> order[(k.toLong() * order.size / keep).toInt()] }.chunked(perSlide)
        }
    }
    return listOf(CarouselSlide.Cover) + chunks.map { CarouselSlide.Photos(it) } +
        listOfNotNull(CarouselSlide.Route.takeIf { hasRoute }) + CarouselSlide.Closing
}
