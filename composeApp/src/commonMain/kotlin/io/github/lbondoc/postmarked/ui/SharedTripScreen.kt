package io.github.lbondoc.postmarked.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import io.github.lbondoc.postmarked.data.MediaKind
import io.github.lbondoc.postmarked.domain.route.RoutedLeg
import io.github.lbondoc.postmarked.data.SharedMedia
import io.github.lbondoc.postmarked.data.SharedTrip
import io.github.lbondoc.postmarked.data.Shares
import io.github.lbondoc.postmarked.data.TripRoute
import io.github.lbondoc.postmarked.data.toRoutePoint
import io.github.lbondoc.postmarked.domain.route.planRoute
import kotlinx.datetime.daysUntil
import kotlinx.datetime.format

class SharedTripViewModel(private val token: String) : ScreenModel() {
    var shared by mutableStateOf<SharedTrip?>(null)
        private set

    fun load() = run { shared = Shares.open(token) }
}

/**
 * The read-only trip behind a share link (SPEC §7): the photos by day with their notes, a big view of each, and
 * the route drawn on paper. No sign-in, and nothing here can change the trip.
 */
@Composable
fun SharedTripScreen(token: String, vm: SharedTripViewModel = viewModel { SharedTripViewModel(token) }) {
    LaunchedEffect(token) { vm.load() }
    val shared = vm.shared
    var viewing by remember { mutableStateOf<Int?>(null) }
    if (shared == null) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp), Alignment.Center) {
            vm.error?.let { Text("This share link doesn't work any more. Ask for a new one.\n\n($it)") } ?: CircularProgressIndicator()
        }
        return
    }
    val trip = shared.trip
    val media = remember(shared) { shared.media.sortedBy { it.capturedAt } }
    val byDay = remember(media) { media.groupBy { it.asTripMedia().takenOn } }
    val notes = remember(shared) { shared.notes.associate { it.day to it.body } }
    // Straight lines between stops: road routing needs an account, and the shape of the trip is what matters here.
    val route = remember(media) {
        val items = media.map { it.asTripMedia() }
        val plan = planRoute(items.mapNotNull { it.toRoutePoint() })
        TripRoute(plan, plan.legs.map { RoutedLeg(it, listOf(it.from.at, it.to.at), seconds = null, snapped = false) }, emptyList(), items.associateBy { it.id }, null)
    }

    LazyVerticalGrid(GridCells.Adaptive(110.dp), Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PostmarkLogo(size = 44.dp)
                Text(trip.name, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineLarge)
                Text(dateRange(trip.startDate, trip.endDate), style = MaterialTheme.typography.titleMedium)
                Text("Shared with you. You're seeing this trip read-only.", style = MaterialTheme.typography.bodySmall)
                if (route.plan.stops.size > 1) {
                    RouteDrawing(route, trip.startDate, onStop = {}, Modifier.fillMaxWidth().height(260.dp))
                }
            }
        }
        for ((day, items) in byDay.entries.sortedBy { it.key }) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp)) {
                    Text(
                        "Day ${trip.startDate.daysUntil(day) + 1} · ${day.format(DAY)}",
                        Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge,
                    )
                    notes[day]?.let { Text(it, style = MaterialTheme.typography.bodyLarge, fontFamily = handFont(), color = MaterialTheme.colorScheme.secondary) }
                }
            }
            items(items, key = { it.id }) { item ->
                AsyncImage(
                    model = item.thumb,
                    contentDescription = describe(item),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.aspectRatio(1f).padding(1.dp).background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(role = Role.Button, onClickLabel = "Open") { viewing = media.indexOf(item) },
                )
            }
        }
        if (media.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text("No photos yet.", Modifier.padding(16.dp)) }
    }

    viewing?.let { start ->
        Dialog(onDismissRequest = { viewing = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            val pager = rememberPagerState(initialPage = start) { media.size }
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                    val item = media[page]
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        AsyncImage(
                            model = item.display ?: item.poster ?: item.thumb, contentDescription = describe(item),
                            contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            listOfNotNull(item.profile?.displayName, if (item.kind == MediaKind.Video) "Video: play it in the Postmarked app" else null).joinToString(" · "),
                            Modifier.padding(12.dp), color = Color.White, style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                TextButton(onClick = { viewing = null }, Modifier.align(Alignment.TopEnd).padding(8.dp)) { Text("Close", color = Color.White) }
            }
        }
    }
}

private fun describe(item: SharedMedia): String {
    val trip = item.asTripMedia()
    return (if (item.kind == MediaKind.Video) "Video" else "Photo") + " from ${trip.takenOn.format(DAY)}" +
        (item.profile?.let { " by ${it.displayName}" } ?: "")
}
