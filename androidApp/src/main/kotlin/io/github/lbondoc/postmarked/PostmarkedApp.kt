package io.github.lbondoc.postmarked

import android.app.Application
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.lbondoc.postmarked.data.TripCache
import io.github.lbondoc.postmarked.data.supabase
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

fun crashReport(context: android.content.Context) = java.io.File(context.filesDir, "last-crash.txt")

/** Runs before any screen and before background uploads, whichever starts the app. */
class PostmarkedApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Keep the error from a crash so the next launch can offer to share it: there is no other way to see it without a computer.
        val crashFile = crashReport(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { crashFile.writeText(error.stackTraceToString()) }
            previous?.uncaughtException(thread, error) // then crash as usual
        }
        UploadQueue.init(this)
        TripCache.store = AndroidTripCacheStore(this)
        MainScope().launch {
            supabase.auth.sessionStatus.collect { status ->
                if (status is SessionStatus.NotAuthenticated && status.isSignOut) {
                    UploadQueue.clear(this@PostmarkedApp)
                    TripCache.store.clear()
                }
            }
        }
    }
}
