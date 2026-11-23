package io.github.lbondoc.postmarked.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

const val VIEWER_MIN_ZOOM = 1f
const val VIEWER_MAX_ZOOM = 5f
const val VIEWER_DOUBLE_TAP_ZOOM = 2.5f

/**
 * How the viewer's photo is zoomed (SPEC §3.1): [scale] from 1 to 5, and [pan] from the centre in pixels, kept
 * so the photo never leaves the screen: $|t_x| \le (s-1)W/2$, $|t_y| \le (s-1)H/2$ on a W × H page. Pure,
 * so the gesture code only feeds it changes.
 */
data class ZoomState(val scale: Float = VIEWER_MIN_ZOOM, val pan: Offset = Offset.Zero) {
    val zoomed: Boolean get() = scale > VIEWER_MIN_ZOOM

    /**
     * A pinch: [zoomChange] times the scale, about [centroid] (relative to the page centre), plus the fingers'
     * [panChange]. The point under the fingers stays under them, as in any photo app.
     */
    fun transformed(zoomChange: Float, panChange: Offset, centroid: Offset, page: Size): ZoomState {
        val s = (scale * zoomChange).coerceIn(VIEWER_MIN_ZOOM, VIEWER_MAX_ZOOM)
        // Screen point p shows content point (p - t) / s; keeping the centroid's content point fixed gives this.
        val t = centroid - (centroid - pan) * (s / scale) + panChange
        return ZoomState(s, clampPan(t, s, page))
    }

    /** Double tap: back to 1× when zoomed, else 2.5× around [at] (relative to the page centre). */
    fun doubleTapped(at: Offset, page: Size): ZoomState =
        if (zoomed) ZoomState() else ZoomState(VIEWER_DOUBLE_TAP_ZOOM, clampPan(at * (1 - VIEWER_DOUBLE_TAP_ZOOM), VIEWER_DOUBLE_TAP_ZOOM, page))

    /**
     * A one-finger drag of [delta]. While zoomed it pans the photo, clamped; unzoomed it returns null, meaning
     * the drag belongs to the pager (next or previous photo).
     */
    fun swipe(delta: Offset, page: Size): ZoomState? = if (zoomed) copy(pan = clampPan(pan + delta, scale, page)) else null
}

/** [t] kept so a photo scaled by [s] still covers the page's centre: $|t| \le (s-1)\,\text{size}/2$ per axis. */
fun clampPan(t: Offset, s: Float, page: Size): Offset {
    val maxX = (s - 1) * page.width / 2
    val maxY = (s - 1) * page.height / 2
    return Offset(t.x.coerceIn(-maxX, maxX), t.y.coerceIn(-maxY, maxY))
}
