package io.github.lbondoc.postmarked

import android.app.Application
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.lbondoc.postmarked.data.supabase
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/** Runs before any screen and before background uploads, whichever starts the app. */
class PostmarkedApp : Application() {
    override fun onCreate() {
        super.onCreate()
        UploadQueue.init(this)
        MainScope().launch {
            supabase.auth.sessionStatus.collect { status ->
                if (status is SessionStatus.NotAuthenticated && status.isSignOut) UploadQueue.clear(this@PostmarkedApp)
            }
        }
    }
}
