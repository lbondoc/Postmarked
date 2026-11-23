package io.github.lbondoc.postmarked.domain.color

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A colour in OKLab, a "perceptual" colour space: equal distances look like equal differences
 * to people, which plain RGB does not. [l] is lightness 0 (black) to 1 (white); [a] runs green
 * to red and [b] blue to yellow, both about -0.4 to 0.4. See bottosson.github.io/posts/oklab.
 */
data class Oklab(val l: Double, val a: Double, val b: Double) {
    /** How colourful: 0 for greys. */
    val chroma: Double get() = hypot(a, b)

    /** The colour's angle around the wheel in degrees, 0 to 360: red near 30, yellow near 110, green near 140, blue near 265. */
    val hue: Double get() = (atan2(b, a) * 180 / PI + 360) % 360

    fun distanceTo(o: Oklab): Double = sqrt((l - o.l).pow(2) + (a - o.a).pow(2) + (b - o.b).pow(2))

    /** Back to a 0xRRGGBB colour, clipped to what a screen can show. */
    fun toRgb(): Int {
        val l_ = l + 0.3963377774 * a + 0.2158037573 * b
        val m_ = l - 0.1055613458 * a - 0.0638541728 * b
        val s_ = l - 0.0894841775 * a - 1.2914855480 * b
        val lc = l_ * l_ * l_
        val mc = m_ * m_ * m_
        val sc = s_ * s_ * s_
        val r = 4.0767416621 * lc - 3.3077115913 * mc + 0.2309699292 * sc
        val g = -1.2684380046 * lc + 2.6097574011 * mc - 0.3413193965 * sc
        val bl = -0.0041960863 * lc - 0.7034186147 * mc + 1.7076147010 * sc
        return (encode(r) shl 16) or (encode(g) shl 8) or encode(bl)
    }

    companion object {
        /** From a 0xRRGGBB (or 0xAARRGGBB) colour; alpha is ignored. */
        fun fromRgb(rgb: Int): Oklab {
            val r = decode((rgb shr 16) and 0xFF)
            val g = decode((rgb shr 8) and 0xFF)
            val b = decode(rgb and 0xFF)
            val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
            val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
            val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
            return Oklab(
                0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
                1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
                0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
            )
        }
    }
}

// sRGB stores brightness on a curve; the maths needs it straight ("linear") and back.
private fun decode(channel: Int): Double {
    val c = channel / 255.0
    return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
}

private fun encode(linear: Double): Int {
    val c = if (linear <= 0.0031308) 12.92 * linear else 1.055 * linear.pow(1 / 2.4) - 0.055
    return (c * 255).roundToInt().coerceIn(0, 255)
}

fun rgbToHex(rgb: Int): String = "#" + (rgb and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()

fun hexToRgb(hex: String): Int = hex.removePrefix("#").toInt(16)

/** One colour of a palette and the share of the picture it covers (all shares add up to 1). */
data class Swatch(val color: Oklab, val weight: Double) {
    val rgb: Int get() = color.toRgb()
}

/** Below this chroma a colour reads as grey, white or black. */
const val NEUTRAL_CHROMA = 0.04

/** Pixels sampled per picture: plenty for five colours, and fast. */
private const val SAMPLE = 4096

/**
 * The main colours of a picture: k-means clustering of its pixels in OKLab, largest share first.
 * [argb] is the pixels as 0xAARRGGBB; fully transparent ones are skipped. Same input, same answer.
 */
fun palette(argb: IntArray, k: Int = 5): List<Swatch> {
    val step = maxOf(1, argb.size / SAMPLE)
    val points = (argb.indices step step).map { argb[it] }.filter { (it ushr 24) != 0 }.map(Oklab::fromRgb)
    return kMeans(points, DoubleArray(points.size) { 1.0 }, k)
}

/** The trip's colours: its photos' palettes clustered together, each photo counting equally. */
fun mergePalettes(palettes: List<List<Swatch>>, k: Int = 5): List<Swatch> {
    val swatches = palettes.flatten()
    return kMeans(swatches.map { it.color }, swatches.map { it.weight }.toDoubleArray(), k)
}

/**
 * Weighted k-means: [k] centres, each the weighted mean of the points nearest it, repeated
 * until stable. Starts with k-means++ (spread-out starting points) from a fixed seed, so
 * results never change between runs. Empty clusters are dropped.
 */
internal fun kMeans(points: List<Oklab>, weights: DoubleArray, k: Int): List<Swatch> {
    if (points.isEmpty()) return emptyList()
    val random = Random(1)
    val centres = mutableListOf(points[random.nextInt(points.size)])
    while (centres.size < minOf(k, points.size)) {
        // The next centre is likely to be far from the ones so far.
        val far = points.mapIndexed { i, p -> weights[i] * centres.minOf { p.distanceTo(it) }.pow(2) }
        val total = far.sum()
        if (total == 0.0) break // every point already sits on a centre
        var pick = random.nextDouble() * total
        val index = far.indexOfFirst { pick -= it; pick <= 0 }.takeIf { it >= 0 } ?: far.lastIndex
        centres += points[index]
    }
    var assignment = IntArray(points.size) { -1 }
    repeat(30) {
        val next = IntArray(points.size) { i -> centres.indices.minBy { points[i].distanceTo(centres[it]) } }
        if (next.contentEquals(assignment)) return@repeat
        assignment = next
        for (c in centres.indices) {
            var w = 0.0; var l = 0.0; var a = 0.0; var b = 0.0
            for (i in points.indices) if (assignment[i] == c) {
                w += weights[i]; l += weights[i] * points[i].l; a += weights[i] * points[i].a; b += weights[i] * points[i].b
            }
            if (w > 0) centres[c] = Oklab(l / w, a / w, b / w)
        }
    }
    val totalWeight = weights.sum()
    return centres.indices
        .map { c -> Swatch(centres[c], points.indices.filter { assignment[it] == c }.sumOf { weights[it] } / totalWeight) }
        .filter { it.weight > 0 }
        .sortedByDescending { it.weight }
}

/** Degrees between two hues the short way round. */
fun hueDistance(a: Double, b: Double): Double {
    val d = abs(a - b) % 360
    return if (d > 180) 360 - d else d
}

/**
 * How much of a picture is in [target]'s colour family: the shares of its swatches within 30
 * degrees of hue and at least half as vivid (so beige sand isn't "yellow"), or for white, grey
 * or black, of similar lightness. 0 to 1.
 */
fun share(palette: List<Swatch>, target: Oklab): Double =
    if (target.chroma >= NEUTRAL_CHROMA) {
        val minChroma = maxOf(NEUTRAL_CHROMA, target.chroma / 2)
        palette.filter { it.color.chroma >= minChroma && hueDistance(it.color.hue, target.hue) <= 30 }.sumOf { it.weight }
    } else {
        palette.filter { it.color.chroma < NEUTRAL_CHROMA && abs(it.color.l - target.l) <= 0.2 }.sumOf { it.weight }
    }

/** The picture's main colourful swatch, or null when it is all greys. */
fun dominantColour(palette: List<Swatch>): Oklab? = palette.firstOrNull { it.color.chroma >= NEUTRAL_CHROMA }?.color

/**
 * Orders pictures like a rainbow, starting from red. Mostly grey pictures go last, light to dark.
 * [palettes] are in any order; the result is their indices.
 */
fun rainbowOrder(palettes: List<List<Swatch>>): List<Int> {
    val (colourful, grey) = palettes.indices.partition { dominantColour(palettes[it]) != null }
    return colourful.sortedBy { (dominantColour(palettes[it])!!.hue - 20 + 360) % 360 } +
        grey.sortedByDescending { palettes[it].firstOrNull()?.color?.l ?: 0.0 }
}

/**
 * Fills [slots] places so the grid flows through [colours] in reading order (a gradient):
 * each place gets a target colour, and the unused picture whose main colour is nearest takes it.
 * Returns picture indices, one per place (fewer if pictures run out).
 */
fun gradientOrder(palettes: List<List<Swatch>>, colours: List<Oklab>, slots: Int): List<Int> {
    require(colours.isNotEmpty())
    val main = palettes.map { dominantColour(it) ?: it.firstOrNull()?.color }
    val unused = palettes.indices.filter { main[it] != null }.toMutableList()
    return (0 until minOf(slots, unused.size)).map { slot ->
        val target = along(colours, if (slots == 1) 0.0 else slot.toDouble() / (slots - 1))
        unused.minBy { main[it]!!.distanceTo(target) }.also { unused.remove(it) }
    }
}

/**
 * The colour [t] (0 to 1) of the way through [colours], travelling round the colour wheel
 * (yellow to blue passes through green) rather than straight across it (through grey).
 */
private fun along(colours: List<Oklab>, t: Double): Oklab {
    if (colours.size == 1) return colours[0]
    val x = t * (colours.size - 1)
    val i = minOf(x.toInt(), colours.size - 2)
    val f = x - i
    val (p, q) = colours[i] to colours[i + 1]
    var turn = q.hue - p.hue
    if (turn > 180) turn -= 360 else if (turn < -180) turn += 360 // the short way round
    val hue = (p.hue + turn * f) * PI / 180
    val chroma = p.chroma + (q.chroma - p.chroma) * f
    return Oklab(p.l + (q.l - p.l) * f, chroma * kotlin.math.cos(hue), chroma * kotlin.math.sin(hue))
}

/** WCAG contrast ratio between two 0xRRGGBB colours, 1 (none) to 21 (black on white). */
fun contrast(rgb1: Int, rgb2: Int): Double {
    fun luminance(rgb: Int) =
        0.2126 * decode((rgb shr 16) and 0xFF) + 0.7152 * decode((rgb shr 8) and 0xFF) + 0.0722 * decode(rgb and 0xFF)
    val (hi, lo) = listOf(luminance(rgb1), luminance(rgb2)).sortedDescending()
    return (hi + 0.05) / (lo + 0.05)
}

/**
 * Text colour for a [background]: the first of [preferred], then warm ink, then white, then
 * black, that is readable on it (4.5:1, WCAG AA). Black or white always is, on any background.
 */
fun readableOn(background: Int, preferred: List<Int> = emptyList()): Int =
    (preferred + listOf(0x1F1B16, 0xFFFFFF)).firstOrNull { contrast(it, background) >= 4.5 }
        ?: listOf(0x000000, 0xFFFFFF).maxBy { contrast(it, background) }

/** A plain name for a colour, for labels and screen readers: "Yellow", "Brown", "Grey". */
fun colourName(c: Oklab): String {
    if (c.chroma < NEUTRAL_CHROMA) return when {
        c.l > 0.85 -> "White"
        c.l < 0.3 -> "Black"
        else -> "Grey"
    }
    val h = c.hue
    return when {
        h < 20 || h >= 345 -> "Pink"
        h < 45 -> "Red"
        h < 75 -> if (c.l < 0.6) "Brown" else "Orange"
        h < 115 -> if (c.l < 0.6) "Brown" else "Yellow"
        h < 170 -> "Green"
        h < 215 -> "Teal"
        h < 275 -> "Blue"
        h < 320 -> "Purple"
        else -> "Pink"
    }
}
