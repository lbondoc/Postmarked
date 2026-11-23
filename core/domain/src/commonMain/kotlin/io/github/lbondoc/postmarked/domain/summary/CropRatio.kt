package io.github.lbondoc.postmarked.domain.summary

/**
 * The shape a photo's tile takes in an adaptive layout (SPEC §8.4). [Original], the default, is the photo's own
 * shape. [Free] isn't tied to the photo: the tile keeps the layout's own slot shape. Strict grids ignore all of
 * these, because their tiles must line up.
 */
enum class CropRatio(val label: String, val aspect: Double?) {
    Original("Original", null),
    Square("1:1", 1.0),
    Portrait("4:5", 4.0 / 5),
    Tall("3:4", 3.0 / 4),
    Wide("16:9", 16.0 / 9),
    Free("Free", null);

    companion object {
        /** A saved name back to a ratio; a name a newer version adds reads as [Original]. */
        fun named(name: String?): CropRatio = entries.firstOrNull { it.name == name } ?: Original
    }
}

/**
 * The shape [arrange] should give a tile: the chosen ratio, else the photo's own ([photoAspect]) for Original.
 * Null leaves the slot as the layout designed it (Free, or a photo whose shape isn't known yet).
 */
fun tileAspect(ratio: CropRatio, photoAspect: Double?): Double? = when (ratio) {
    CropRatio.Original -> photoAspect
    CropRatio.Free -> null
    else -> ratio.aspect
}

/** A strict grid's tile ratio as one of the picker's choices, when it is one; the picker shows only this. */
fun lockedRatio(slotAspect: Double): CropRatio? =
    CropRatio.entries.firstOrNull { it.aspect != null && kotlin.math.abs(it.aspect - slotAspect) < 0.01 }
