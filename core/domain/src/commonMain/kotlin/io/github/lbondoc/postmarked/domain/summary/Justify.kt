package io.github.lbondoc.postmarked.domain.summary

import kotlin.math.abs

/** One row of a justified layout: photos [start] until [end] (exclusive), all [height] tall. */
data class JustifiedRow(val start: Int, val end: Int, val height: Double)

/**
 * Justified rows (SPEC §8.4): photos with aspects $r_i = w_i / h_i$ in order, in rows exactly [width] wide with
 * [gap] between photos. A row of $n$ photos is $h = (W - g(n-1)) / \sum r_i$ tall, and rows break where $h$ comes
 * closest to [targetHeight]. A photo too wide to share a row (alone it would already be no taller than the
 * target) gets a row to itself. Every photo keeps its own shape: portraits stay portrait.
 */
fun justify(aspects: List<Double>, width: Double, gap: Double, targetHeight: Double): List<JustifiedRow> {
    fun height(from: Int, until: Int) = (width - gap * (until - from - 1)) / (from until until).sumOf { aspects[it] }
    val rows = mutableListOf<JustifiedRow>()
    var start = 0
    var i = 0
    while (i < aspects.size) {
        if (width / aspects[i] <= targetHeight) {
            // Very wide: close what came before, then this one alone.
            if (i > start) rows += JustifiedRow(start, i, height(start, i))
            rows += JustifiedRow(i, i + 1, height(i, i + 1))
            start = i + 1
        } else if (height(start, i + 1) <= targetHeight) {
            // Adding this photo brings the row down to the target: keep it or not, whichever is closer.
            val without = if (i > start) height(start, i) else Double.MAX_VALUE
            if (abs(without - targetHeight) < abs(height(start, i + 1) - targetHeight)) {
                rows += JustifiedRow(start, i, without)
                start = i
                continue // this photo starts the next row
            }
            rows += JustifiedRow(start, i + 1, height(start, i + 1))
            start = i + 1
        }
        i++
    }
    if (start < aspects.size) rows += JustifiedRow(start, aspects.size, height(start, aspects.size))
    return rows
}
