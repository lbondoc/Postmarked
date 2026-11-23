package io.github.lbondoc.postmarked.domain.media

/** Photos taken this close together, by anyone, may be the same shot. */
const val SIMILAR_WITHIN_MS = 10_000L

/** Fingerprints differing in at most this many of 64 bits look alike (bursts, two friends shooting one view). */
const val SIMILAR_MAX_BITS = 10

/**
 * A 64-bit "difference hash" of a picture: from a 9 x 8 grayscale version, each bit says whether
 * a pixel is brighter than its right-hand neighbour. Similar-looking pictures get similar bits,
 * whatever their size or compression. [gray] is 72 brightness values, row by row.
 */
fun differenceHash(gray: IntArray): Long {
    require(gray.size == 72) { "A difference hash needs a 9 x 8 grayscale image" }
    var hash = 0L
    for (row in 0 until 8) for (col in 0 until 8) {
        hash = (hash shl 1) or if (gray[row * 9 + col] > gray[row * 9 + col + 1]) 1L else 0L
    }
    return hash
}

/** How many of the 64 bits differ. */
fun differingBits(a: Long, b: Long): Int = (a xor b).countOneBits()

/**
 * How sharp a picture is: the variance of its Laplacian (how strongly each pixel differs from
 * its four neighbours). Blurry pictures have soft edges and score low. Only comparable between
 * pictures of the same scene at the same size, which is what choosing within a burst needs.
 */
fun sharpness(gray: IntArray, width: Int, height: Int): Double {
    require(gray.size == width * height)
    if (width < 3 || height < 3) return 0.0
    var sum = 0.0
    var sumOfSquares = 0.0
    var n = 0
    for (y in 1 until height - 1) for (x in 1 until width - 1) {
        val i = y * width + x
        val laplacian = (gray[i - 1] + gray[i + 1] + gray[i - width] + gray[i + width] - 4 * gray[i]).toDouble()
        sum += laplacian
        sumOfSquares += laplacian * laplacian
        n++
    }
    val mean = sum / n
    return sumOfSquares / n - mean * mean
}

/** What grouping needs to know about one photo. Videos are never grouped. */
data class Shot(val id: String, val timeMs: Long, val dhash: Long?, val sharpness: Double?)

/**
 * Groups photos that are the same moment: each joins the group before it when taken within
 * [SIMILAR_WITHIN_MS] of that group's last photo and looking alike. Photos without a fingerprint
 * (uploaded before fingerprints existed) stay on their own. The sharpest photo of each group
 * comes first: that is the one to show.
 */
fun groupSimilar(shots: List<Shot>): List<List<Shot>> {
    val groups = mutableListOf<MutableList<Shot>>()
    for (shot in shots.sortedBy { it.timeMs }) {
        val group = groups.lastOrNull()
        val last = group?.last()
        val alike = last != null && shot.dhash != null && last.dhash != null &&
            shot.timeMs - last.timeMs <= SIMILAR_WITHIN_MS && differingBits(shot.dhash, last.dhash) <= SIMILAR_MAX_BITS
        if (alike) group.add(shot) else groups += mutableListOf(shot)
    }
    // sortedByDescending keeps the earlier shot first on a tie, and missing sharpness counts as lowest.
    return groups.map { group -> group.sortedByDescending { it.sharpness ?: -1.0 } }
}
