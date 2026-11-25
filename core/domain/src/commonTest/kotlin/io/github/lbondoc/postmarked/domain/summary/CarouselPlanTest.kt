package io.github.lbondoc.postmarked.domain.summary

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CarouselPlanTest {
    private fun photosOf(plan: List<CarouselSlide>) = plan.filterIsInstance<CarouselSlide.Photos>().map { it.photos }

    @Test
    fun photosStayInTimeOrder() {
        // Given out of order: index 2 was taken first.
        val photos = listOf(CarouselPhoto(300, 0), CarouselPhoto(200, 0), CarouselPhoto(100, 0), CarouselPhoto(400, 1))
        val plan = carouselPlan(photos, perSlide = 2, hasRoute = true)
        assertEquals(listOf(2, 1, 0, 3), photosOf(plan).flatten())
        assertEquals(CarouselSlide.Cover, plan.first())
        assertEquals(listOf(CarouselSlide.Route, CarouselSlide.Closing), plan.takeLast(2))
    }

    @Test
    fun slidesSplitAtDayBoundariesWhenTheyFit() {
        // Day 0 has 3 photos, day 1 has 2: with 4 to a slide, each day gets its own slide.
        val photos = List(3) { CarouselPhoto(it.toLong(), 0) } + List(2) { CarouselPhoto(10L + it, 1) }
        assertEquals(listOf(listOf(0, 1, 2), listOf(3, 4)), photosOf(carouselPlan(photos, perSlide = 4, hasRoute = false)))
    }

    @Test
    fun daysShareSlidesWhenSplittingWouldGoPastTheLimit() {
        // 20 days of one photo each, 2 to a slide: 20 day slides won't fit in 17, but 10 shared ones do.
        val photos = List(20) { CarouselPhoto(it.toLong(), it) }
        val plan = carouselPlan(photos, perSlide = 2, hasRoute = true)
        assertEquals(10, photosOf(plan).size)
        assertEquals((0 until 20).toList(), photosOf(plan).flatten())
    }

    @Test
    fun neverMoreThanTwentySlides() {
        for (count in listOf(0, 1, 17, 18, 19, 40, 500)) for (hasRoute in listOf(true, false)) {
            val photos = List(count) { CarouselPhoto(it.toLong(), it / 7) }
            val plan = carouselPlan(photos, perSlide = 1, hasRoute = hasRoute)
            assertTrue(plan.size <= MAX_SLIDES, "$count photos: ${plan.size} slides")
            val shown = photosOf(plan).flatten()
            assertEquals(shown.sorted(), shown, "$count photos: still in time order")
            assertEquals(shown.size, shown.toSet().size, "no photo twice")
            if (count <= MAX_SLIDES - 2 - (if (hasRoute) 1 else 0)) assertEquals(count, shown.size, "everything fits")
        }
    }

    @Test
    fun theRouteSlideIsLeftOutWithoutARoute() {
        val plan = carouselPlan(listOf(CarouselPhoto(0, 0)), perSlide = 1, hasRoute = false)
        assertEquals(listOf(CarouselSlide.Cover, CarouselSlide.Photos(listOf(0)), CarouselSlide.Closing), plan)
    }
}
