package io.github.lbondoc.postmarked.domain.color

import kotlin.math.hypot

/** The largest colour-cast correction, in OKLab units: enough to warm or cool a photo, not enough to tint it green. */
private const val MAX_CAST_SHIFT = 0.04

/** How strongly shadows and highlights take on the target's dark and light colours. */
private const val TONING = 0.10

/**
 * A colour adjustment as an affine map on RGB (each 0 to 1): out = m · (r, g, b) + offset.
 * Row-major 3 x 3 [m]. Being affine, it can be drawn by the GPU as a colour matrix, so the
 * original photo is never changed: the look is applied each time it is shown.
 */
data class Grade(val m: List<Double>, val offset: List<Double>) {
    fun apply(rgb: Int): Int {
        val c = doubleArrayOf(((rgb shr 16) and 0xFF) / 255.0, ((rgb shr 8) and 0xFF) / 255.0, (rgb and 0xFF) / 255.0)
        fun channel(row: Int) = ((m[row * 3] * c[0] + m[row * 3 + 1] * c[1] + m[row * 3 + 2] * c[2] + offset[row]) * 255)
            .toInt().coerceIn(0, 255)
        return (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
    }

    /** Only [t] (0 to 1) of the way from no change to this grade. */
    fun scaled(t: Double): Grade = Grade(
        m = m.mapIndexed { i, v -> IDENTITY.m[i] + (v - IDENTITY.m[i]) * t },
        offset = offset.map { it * t },
    )

    /** 4 x 5 row-major, the layout colour-matrix filters use, with offsets in 0..255 units. */
    fun toColorMatrix(): FloatArray = FloatArray(20).also { f ->
        for (row in 0 until 3) {
            for (col in 0 until 3) f[row * 5 + col] = m[row * 3 + col].toFloat()
            f[row * 5 + 4] = (offset[row] * 255).toFloat()
        }
        f[18] = 1f // alpha unchanged
    }

    companion object {
        val IDENTITY = Grade(listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0), listOf(0.0, 0.0, 0.0))
    }
}

// How much each channel adds to perceived brightness (Rec. 709), for keeping brightness steady.
private val LUMA = listOf(0.2126, 0.7152, 0.0722)

/**
 * A grade that moves a photo with palette [photo] toward the look of [target], at [intensity]
 * 0 to 1. Three steps, each worked out in OKLab and expressed in RGB:
 * 1. White balance: shift the photo's overall colour cast toward the target's.
 * 2. Saturation: scale colourfulness toward the target's (between 0.7x and 1.3x).
 * 3. Split toning: tint shadows toward the target's darkest colour and highlights toward its lightest.
 */
fun harmonize(photo: List<Swatch>, target: List<Swatch>, intensity: Double): Grade {
    if (photo.isEmpty() || target.isEmpty() || intensity <= 0) return Grade.IDENTITY

    fun cast(p: List<Swatch>) = p.sumOf { it.weight * it.color.a } to p.sumOf { it.weight * it.color.b }
    fun chroma(p: List<Swatch>) = p.sumOf { it.weight * it.color.chroma }

    // 1. White balance: per-channel gains that take a mid grey with the photo's cast to one with the target's.
    val (pa, pb) = cast(photo)
    val (ta, tb) = cast(target)
    var da = ta - pa
    var db = tb - pb
    val shift = hypot(da, db)
    if (shift > MAX_CAST_SHIFT) { da *= MAX_CAST_SHIFT / shift; db *= MAX_CAST_SHIFT / shift }
    val before = Oklab(0.6, pa, pb).toRgb()
    val after = Oklab(0.6, pa + da, pb + db).toRgb()
    val gains = (0 until 3).map { ch ->
        val shiftBits = 16 - ch * 8
        val x = ((before shr shiftBits) and 0xFF).coerceAtLeast(1)
        (((after shr shiftBits) and 0xFF).toDouble() / x).coerceIn(0.8, 1.25)
    }

    // 2. Saturation: blend each channel with brightness; s > 1 pushes colours away from grey.
    val s = (chroma(target) / chroma(photo).coerceAtLeast(0.01)).coerceIn(0.7, 1.3)
    val m = MutableList(9) { 0.0 }
    for (row in 0 until 3) for (col in 0 until 3) {
        val saturation = (1 - s) * LUMA[col] + if (row == col) s else 0.0
        m[row * 3 + col] = saturation * gains[col]
    }

    // 3. Split toning: add (dark colour - its brightness) in shadows, (light colour - its brightness) in highlights.
    // Brightness Y is itself linear in RGB, so the whole thing stays one affine map.
    val dark = target.minBy { it.color.l }.rgb
    val light = target.maxBy { it.color.l }.rgb
    fun tint(rgb: Int): List<Double> {
        val c = listOf((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF).map { it / 255.0 }
        val y = c.indices.sumOf { LUMA[it] * c[it] }
        return c.map { it - y }
    }
    val shadowTint = tint(dark)
    val lightTint = tint(light)
    val offset = MutableList(3) { 0.0 }
    for (row in 0 until 3) {
        offset[row] = TONING * shadowTint[row]
        for (col in 0 until 3) m[row * 3 + col] += TONING * (lightTint[row] - shadowTint[row]) * LUMA[col]
    }
    return Grade(m, offset).scaled(intensity.coerceIn(0.0, 1.0))
}
