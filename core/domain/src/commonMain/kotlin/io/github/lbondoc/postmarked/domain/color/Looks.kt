package io.github.lbondoc.postmarked.domain.color

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Filters and adjustments as affine colour maps ([Grade]s), so the phone's GPU applies them
// while drawing and the photo itself is never changed. The recipes follow the CSS filter
// functions (sepia, saturate, hue-rotate...), which is also how the design mock-ups preview them.

private fun diagonal(r: Double, g: Double, b: Double, offset: Double = 0.0) =
    Grade(listOf(r, 0.0, 0.0, 0.0, g, 0.0, 0.0, 0.0, b), listOf(offset, offset, offset))

/** Multiplies every channel: above 1 brighter, below 1 darker. */
fun brightness(k: Double) = diagonal(k, k, k)

/** Spreads colours away from (above 1) or towards (below 1) mid-grey, which stays put. */
fun contrast(k: Double) = diagonal(k, k, k, 0.5 * (1 - k))

/** 0 is greyscale, 1 unchanged, above 1 more colourful. Brightness (Rec. 709 luma) is kept. */
fun saturation(k: Double): Grade {
    val luma = listOf(0.2126, 0.7152, 0.0722)
    return Grade(List(9) { i -> luma[i % 3] * (1 - k) + if (i / 3 == i % 3) k else 0.0 }, listOf(0.0, 0.0, 0.0))
}

/** The old-photo brown tone, [amount] 0 (none) to 1 (full). */
fun sepia(amount: Double) = Grade(
    listOf(0.393, 0.769, 0.189, 0.349, 0.686, 0.168, 0.272, 0.534, 0.131), listOf(0.0, 0.0, 0.0),
).scaled(amount)

/** Turns every hue round the colour wheel by [degrees], keeping brightness. */
fun hueRotate(degrees: Double): Grade {
    val a = degrees * PI / 180
    val c = cos(a)
    val s = sin(a)
    return Grade(
        listOf(
            0.213 + c * 0.787 - s * 0.213, 0.715 - c * 0.715 - s * 0.715, 0.072 - c * 0.072 + s * 0.928,
            0.213 - c * 0.213 + s * 0.143, 0.715 + c * 0.285 + s * 0.140, 0.072 - c * 0.072 - s * 0.283,
            0.213 - c * 0.213 - s * 0.787, 0.715 - c * 0.715 + s * 0.715, 0.072 + c * 0.928 + s * 0.072,
        ),
        listOf(0.0, 0.0, 0.0),
    )
}

/** This grade, then [next] on its result. */
infix fun Grade.then(next: Grade): Grade {
    val n = next.m
    val product = List(9) { i ->
        val row = i / 3
        val col = i % 3
        (0 until 3).sumOf { k -> n[row * 3 + k] * m[k * 3 + col] }
    }
    val shifted = List(3) { row -> (0 until 3).sumOf { k -> n[row * 3 + k] * offset[k] } + next.offset[row] }
    return Grade(product, shifted)
}

private fun chain(vararg grades: Grade) = grades.reduce { a, b -> a then b }

/** The ready-made looks, each a chain of the steps above. */
enum class Filter(val label: String, val grade: Grade) {
    Original("Original", Grade.IDENTITY),
    Film("Film", chain(contrast(1.12), saturation(0.85), sepia(0.15))),
    Faded("Faded", chain(contrast(0.82), brightness(1.08), saturation(0.75))),
    WarmRoad("Warm road", chain(sepia(0.28), saturation(1.15))),
    Golden("Golden", chain(sepia(0.4), saturation(1.3), brightness(1.04))),
    Coastal("Coastal", chain(saturation(0.9), hueRotate(-12.0), brightness(1.05))),
    BlackAndWhite("B&W", chain(saturation(0.0), contrast(1.1))),
}

/**
 * One slider each. Values run -1 to 1 with 0 meaning "no change", except [Fade] (0 to 1).
 * [Tint] is offered for single photos only: on a whole summary it fights the colour scheme.
 */
enum class Adjustment(val label: String, val min: Double) {
    Light("Light", -1.0),
    Contrast("Contrast", -1.0),
    Warmth("Warmth", -1.0),
    Tint("Tint", -1.0),
    Colour("Colour", -1.0),
    Fade("Fade", 0.0);

    fun grade(v: Double): Grade = when (this) {
        Light -> brightness(1 + 0.4 * v)
        Contrast -> contrast(1 + 0.5 * v)
        // Warmer: more red, less blue. Positive tint leans magenta, negative green.
        Warmth -> diagonal(1 + 0.12 * v, 1 + 0.02 * v, 1 - 0.12 * v)
        Tint -> diagonal(1 + 0.05 * v, 1 - 0.08 * v, 1 + 0.05 * v)
        Colour -> saturation(1 + v)
        // Faded film: blacks lifted to grey, whites dimmed, colour softened.
        Fade -> diagonal(1 - 0.25 * v, 1 - 0.25 * v, 1 - 0.25 * v, 0.1 * v) then saturation(1 - 0.3 * v)
    }
}

/** A filter at a [strength] (0 to 1), then adjustments, in [Adjustment] order. */
data class Look(val filter: Filter = Filter.Original, val strength: Double = 1.0, val adjust: Map<Adjustment, Double> = emptyMap()) {
    val isPlain get() = (filter == Filter.Original || strength <= 0) && adjust.values.all { it == 0.0 }

    fun grade(): Grade = Adjustment.entries.fold(filter.grade.scaled(strength.coerceIn(0.0, 1.0))) { g, a ->
        val v = adjust[a] ?: 0.0
        if (v == 0.0) g else g then a.grade(v.coerceIn(a.min, 1.0))
    }
}
