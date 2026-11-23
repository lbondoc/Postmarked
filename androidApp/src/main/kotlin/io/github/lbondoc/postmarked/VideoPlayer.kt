package io.github.lbondoc.postmarked

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/** Plays a video from a link, with Android's standard controls. Starts at once; stops when it leaves the screen. */
@Composable
fun VideoPlayer(url: String, modifier: Modifier) {
    val context = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } } // frees the decoder and network connection
    // PlayerView is a classic Android view; AndroidView places it inside Compose.
    AndroidView(factory = { PlayerView(it).apply { this.player = player } }, modifier = modifier)
}
