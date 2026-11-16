package io.github.lbondoc.postmarked

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.lbondoc.postmarked.spike.GpsSpikeScreen
import io.github.lbondoc.postmarked.spike.SpikeItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val mediaPermissions: Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
        )
        Build.VERSION.SDK_INT >= 29 -> arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun granted(permission: String) =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** What we were actually given, in words, so a screenshot of the screen is a full bug report. */
    private fun describeAccess(): List<String> {
        val library = when {
            Build.VERSION.SDK_INT < 33 -> if (granted(Manifest.permission.READ_EXTERNAL_STORAGE)) "full" else "none"
            granted(Manifest.permission.READ_MEDIA_IMAGES) && granted(Manifest.permission.READ_MEDIA_VIDEO) -> "full"
            Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ->
                "limited (only the items you selected)"
            else -> "none"
        }
        val location = when {
            Build.VERSION.SDK_INT < 29 -> "not needed on this Android version"
            granted(Manifest.permission.ACCESS_MEDIA_LOCATION) -> "granted"
            else -> "NOT granted (GPS will be blank)"
        }
        return listOf(
            "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "Photo library access: $library",
            "Media location permission: $location",
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // `by` + mutableStateOf: Compose redraws whatever reads these when they change.
            var items by remember { mutableStateOf<List<SpikeItem>?>(null) }
            var scanning by remember { mutableStateOf(false) }
            var access by remember { mutableStateOf(describeAccess()) }
            val scope = rememberCoroutineScope()

            // Runs when the system permission dialog closes, whatever the user chose:
            // the scan then shows exactly what that choice lets us read.
            val askThenScan = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) {
                access = describeAccess()
                scanning = true
                scope.launch {
                    // MediaStore and file reads block, so hop to the IO thread pool and come back.
                    val result = withContext(Dispatchers.IO) { runCatching { scanRecentMedia(applicationContext) } }
                    items = result.getOrElse { emptyList() }
                    result.exceptionOrNull()?.let { access = access + "Scan failed: $it" }
                    scanning = false
                }
            }

            GpsSpikeScreen(
                items = items,
                scanning = scanning,
                access = access,
                onScan = { askThenScan.launch(mediaPermissions) },
            )
        }
    }
}
