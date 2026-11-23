package io.github.lbondoc.postmarked.domain.summary

/** A rectangle in pixels. */
data class Box(val left: Double, val top: Double, val width: Double, val height: Double)

/** The largest box of shape [aspect] (width / height) that fits centred in [area]. */
fun fit(aspect: Double, area: Box): Box =
    if (area.width / area.height > aspect) {
        val w = area.height * aspect
        Box(area.left + (area.width - w) / 2, area.top, w, area.height)
    } else {
        val h = area.width / aspect
        Box(area.left, area.top + (area.height - h) / 2, area.width, h)
    }

/**
 * The part of a [srcWidth] x [srcHeight] picture to show so it fills a frame of shape [aspect]
 * without stretching: the centre, trimmed on the long side ("centre crop").
 */
fun cropToFill(srcWidth: Double, srcHeight: Double, aspect: Double): Box =
    fit(aspect, Box(0.0, 0.0, srcWidth, srcHeight))

/**
 * The order to post a 3-column grid's tiles so the profile shows them as laid out. Instagram
 * puts the newest post top-left, so the bottom-right tile goes first and the top-left last.
 * Returns tile indices (reading order) in posting order.
 */
fun postingOrder(tiles: Int): List<Int> = (tiles - 1 downTo 0).toList()
