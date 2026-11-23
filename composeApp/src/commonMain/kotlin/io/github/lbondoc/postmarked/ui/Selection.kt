package io.github.lbondoc.postmarked.ui

import io.github.lbondoc.postmarked.data.TripMedia

/** The run a press-and-drag covers in [order], from where it started ([anchor]) to where the finger is ([here]). */
fun dragRange(order: List<String>, anchor: Int, here: Int): List<String> = order.subList(minOf(anchor, here), maxOf(anchor, here) + 1)

/** The selection during a drag: what was selected when it began ([base]), with [range] all set to [on]. */
fun selectRange(base: Set<String>, range: List<String>, on: Boolean): Set<String> = if (on) base + range else base - range.toSet()

fun Set<String>.toggled(id: String): Set<String> = if (id in this) this - id else this + id

/** Of [items], the ones [me] may delete (their own), and how many others are skipped. */
fun ownItems(items: List<TripMedia>, me: String?): Pair<List<TripMedia>, Int> {
    val mine = items.filter { me != null && it.contributor == me }
    return mine to (items.size - mine.size)
}

/** "2 not yours, skipped", or null when everything is yours. */
fun skippedNote(skipped: Int): String? = if (skipped > 0) "$skipped not yours, skipped" else null
