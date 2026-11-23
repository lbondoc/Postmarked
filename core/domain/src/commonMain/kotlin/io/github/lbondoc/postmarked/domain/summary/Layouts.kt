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

/**
 * The summary layouts. [aspect] is width / height of the whole canvas; [textSlots] are the
 * slots that start as text tiles when a summary is generated.
 */
enum class Layout(val label: String, val aspect: Double, val slots: List<Slot>, val textSlots: Set<Int> = emptySet()) {
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
    ),
}
