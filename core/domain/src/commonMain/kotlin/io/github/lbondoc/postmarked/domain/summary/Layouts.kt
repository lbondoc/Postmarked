package io.github.lbondoc.postmarked.domain.summary

/** How a photo is framed in its slot. */
enum class Frame { None, Polaroid, Film }

/**
 * One place on a summary's canvas, as fractions of the canvas: [x], [y] from the top-left,
 * [w], [h] in size, turned by [rotation] degrees about its centre.
 */
data class Slot(val x: Double, val y: Double, val w: Double, val h: Double, val rotation: Double = 0.0, val frame: Frame = Frame.None)

/** Gap between photos, as a fraction of the canvas width. */
private const val GAP = 0.006

/** Square tiles with a gap of 2% of a tile between them; the canvas takes whatever shape that makes. */
private class SquareGrid(cols: Int, rows: Int) {
    private val gap = 0.02
    private val width = cols + (cols - 1) * gap // in tiles
    private val height = rows + (rows - 1) * gap
    val aspect = width / height
    val slots = (0 until rows).flatMap { r ->
        (0 until cols).map { c -> Slot(c * (1 + gap) / width, r * (1 + gap) / height, 1 / width, 1 / height) }
    }
}

private val grid3x3 = SquareGrid(3, 3)
private val grid2x3 = SquareGrid(2, 3)

/** One carousel slide's shape (Instagram portrait, 4:5); a panorama is this many slides wide. */
private const val SLIDE_ASPECT = 4.0 / 5

/**
 * The summary layouts. [aspect] is width / height of the whole canvas; [textSlots] are the
 * slots that start as text tiles when a summary is generated. [slides] above 1 is a panorama: the canvas is
 * that many carousel slides wide, and a carousel export cuts it into them ([panoramaSlices]).
 */
enum class Layout(
    val label: String,
    val aspect: Double,
    val slots: List<Slot>,
    val textSlots: Set<Int> = emptySet(),
    val slides: Int = 1,
    /** Slots follow each photo's own shape (SPEC §8.4); strict grids keep fixed tile shapes. */
    val adaptive: Boolean = false,
) {
    Grid3x3("3 × 3", grid3x3.aspect, grid3x3.slots, textSlots = setOf(4)),
    Grid2x3("2 × 3", grid2x3.aspect, grid2x3.slots, textSlots = setOf(3)),
    Hero(
        "1 large + 4 small", 4.0 / 5,
        listOf(Slot(0.0, 0.0, 1.0, 0.75 - GAP)) + (0 until 4).map { i ->
            val w = (1 - 3 * GAP) / 4
            Slot(i * (w + GAP), 0.75, w, 0.25)
        },
    ),
    Filmstrip(
        "Filmstrip", 9.0 / 16,
        (0 until 4).map { i -> Slot(0.14, 0.04 + i * 0.235, 0.72, 0.215, frame = Frame.Film) },
        adaptive = true,
    ),
    Polaroids(
        "Polaroid collage", 4.0 / 5,
        listOf(
            Slot(0.05, 0.04, 0.46, 0.42, rotation = -7.0, frame = Frame.Polaroid),
            Slot(0.50, 0.07, 0.44, 0.40, rotation = 6.0, frame = Frame.Polaroid),
            Slot(0.27, 0.32, 0.46, 0.42, rotation = -2.0, frame = Frame.Polaroid),
            Slot(0.04, 0.55, 0.44, 0.40, rotation = 8.0, frame = Frame.Polaroid),
            Slot(0.52, 0.56, 0.44, 0.40, rotation = -5.0, frame = Frame.Polaroid),
        ),
        adaptive = true,
    ),
    Magazine(
        "Magazine", 4.0 / 5,
        listOf(
            Slot(0.0, 0.0, 1.0, 0.62 - GAP),
            Slot(0.0, 0.62, 0.5 - GAP / 2, 0.38),
            Slot(0.5 + GAP / 2, 0.62, 0.5 - GAP / 2, 0.19 - GAP / 2),
            Slot(0.5 + GAP / 2, 0.81 + GAP / 2, 0.5 - GAP / 2, 0.19 - GAP / 2),
        ),
        textSlots = setOf(1),
        adaptive = true,
    ),
    // Two photos, one above the other.
    TwoUp("2-up", 4.0 / 5, listOf(Slot(0.0, 0.0, 1.0, 0.5 - GAP / 2), Slot(0.0, 0.5 + GAP / 2, 1.0, 0.5 - GAP / 2))),
    // Four photos in a square 2 × 2 grid.
    FourUp(
        "4-up", 1.0,
        (0 until 2).flatMap { r -> (0 until 2).map { c -> Slot(c * (0.5 + GAP / 2), r * (0.5 + GAP / 2), 0.5 - GAP / 2, 0.5 - GAP / 2) } },
    ),
    // One wide photo across 2 or 3 carousel slides, joining up as they are swiped.
    Panorama2("Panorama, 2 slides", 2 * SLIDE_ASPECT, listOf(Slot(0.0, 0.0, 1.0, 1.0)), slides = 2),
    Panorama3("Panorama, 3 slides", 3 * SLIDE_ASPECT, listOf(Slot(0.0, 0.0, 1.0, 1.0)), slides = 3),
    // Rows that each fill the width, every photo at its own shape (shown here for six 4:3 photos).
    JustifiedRows("Justified rows", justified(List(JUSTIFIED_PHOTOS) { null }).aspect, justified(List(JUSTIFIED_PHOTOS) { null }).slots, adaptive = true),
}

private const val JUSTIFIED_PHOTOS = 6
private const val UNKNOWN_ASPECT = 4.0 / 3 // until a photo's real shape is known: most phone photos
private const val ROW_HEIGHT = 0.36 // target row height, as a fraction of the width: two or three photos a row

/** A layout placed for particular photos: the canvas's shape and where each slot goes. */
data class Arrangement(val aspect: Double, val slots: List<Slot>)

/**
 * This layout placed for the photos in its tiles, [aspects] by slot (null for a text or colour tile, or a photo
 * whose shape isn't known yet). Strict layouts stay as they are. Adaptive ones give each photo its own shape:
 * justified rows reflow completely; the others fit the photo inside its slot, centred.
 */
fun Layout.arrange(aspects: List<Double?>): Arrangement = when {
    this == Layout.JustifiedRows -> justified(List(slots.size) { aspects.getOrNull(it) })
    adaptive -> Arrangement(aspect, slots.mapIndexed { i, s -> aspects.getOrNull(i)?.let { s.fitted(it, aspect) } ?: s })
    else -> Arrangement(aspect, slots)
}

/** The slot shrunk to [photoAspect] and centred in it, on a canvas of shape [canvasAspect]. */
private fun Slot.fitted(photoAspect: Double, canvasAspect: Double): Slot {
    val realW = w * canvasAspect // in canvas heights
    val (fw, fh) = if (photoAspect * h <= realW) photoAspect * h to h else realW to realW / photoAspect
    val nw = fw / canvasAspect
    return copy(x = x + (w - nw) / 2, y = y + (h - fh) / 2, w = nw, h = fh)
}

private fun justified(aspects: List<Double?>): Arrangement {
    val ratios = aspects.map { it ?: UNKNOWN_ASPECT }
    val rows = justify(ratios, 1.0, GAP, ROW_HEIGHT)
    val total = rows.sumOf { it.height } + GAP * (rows.size - 1) // canvas height, in widths
    val slots = mutableListOf<Slot>()
    var y = 0.0
    for (row in rows) {
        var x = 0.0
        for (i in row.start until row.end) {
            val w = ratios[i] * row.height
            slots += Slot(x, y / total, w, row.height / total)
            x += w + GAP
        }
        y += row.height + GAP
    }
    return Arrangement(1 / total, slots)
}
