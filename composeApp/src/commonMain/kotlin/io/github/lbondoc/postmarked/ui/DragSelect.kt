package io.github.lbondoc.postmarked.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Press and hold on a grid cell, then drag across others to select (or, starting on a selected one, deselect)
 * the whole run, scrolling when held near the top or bottom edge. [order] is the selectable cells' keys in
 * screen order; other cells (headers, buttons) are ignored. With [selectHeld] the held cell is selected as soon
 * as the press is long enough (the gallery: a long press starts selecting); without, only once the finger moves
 * (the import screen, as before). Used by both.
 */
fun Modifier.dragSelect(
    gridState: LazyGridState,
    scope: CoroutineScope,
    key: Any?,
    order: () -> List<String>,
    selected: () -> Set<String>,
    onRange: (base: Set<String>, range: List<String>, on: Boolean) -> Unit,
    selectHeld: Boolean = false,
): Modifier = pointerInput(key) {
    var keys = emptyList<String>()
    var anchor = -1
    var on = true
    var base = emptySet<String>()
    var finger = Offset.Zero
    var edgeScroll: Job? = null
    fun indexAt(at: Offset) = gridState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
        at.x >= info.offset.x && at.x < info.offset.x + info.size.width &&
            at.y >= info.offset.y && at.y < info.offset.y + info.size.height
    }?.let { keys.indexOf(it.key) }?.takeIf { it >= 0 }
    fun selectTo(at: Offset) {
        val here = indexAt(at) ?: return
        onRange(base, dragRange(keys, anchor, here), on)
    }
    val edge = 72.dp.toPx()
    val fastest = 24.dp.toPx() // per frame, with the finger right at the edge
    fun stop() { anchor = -1; edgeScroll?.cancel(); edgeScroll = null }
    detectDragGesturesAfterLongPress(
        onDragStart = { at ->
            keys = order()
            anchor = indexAt(at) ?: -1
            if (anchor >= 0) {
                base = selected()
                on = keys[anchor] !in base // starting on a selected cell deselects
                finger = at
                if (selectHeld) selectTo(at)
                // Holding near the top or bottom edge scrolls, faster the closer to the edge, selecting as it goes.
                edgeScroll = scope.launch {
                    while (isActive) {
                        val speed = when {
                            finger.y < edge -> -(edge - finger.y) / edge * fastest
                            finger.y > size.height - edge -> (finger.y - (size.height - edge)) / edge * fastest
                            else -> 0f
                        }
                        if (speed != 0f && gridState.scrollBy(speed) != 0f) selectTo(finger)
                        delay(16)
                    }
                }
            }
        },
        onDrag = { change, _ ->
            if (anchor < 0) return@detectDragGesturesAfterLongPress
            change.consume()
            finger = change.position
            selectTo(finger)
        },
        onDragEnd = { stop() },
        onDragCancel = { stop() },
    )
}
