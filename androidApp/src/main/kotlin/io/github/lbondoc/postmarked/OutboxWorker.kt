package io.github.lbondoc.postmarked

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.jan.supabase.auth.auth
import io.github.lbondoc.postmarked.data.Outbox
import io.github.lbondoc.postmarked.data.myUserId
import io.github.lbondoc.postmarked.data.supabase
import java.util.concurrent.TimeUnit

/** Sends the outbox once there is a network, even if the app was closed meanwhile (SPEC §4). */
class OutboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        supabase.auth.awaitInitialization() // loads the saved sign-in from disk
        if (myUserId == null) return Result.retry()
        Outbox.flush()
        return if (Outbox.entries.value.isEmpty()) Result.success() else Result.retry()
    }

    companion object {
        fun start(context: Context) {
            val request = OneTimeWorkRequestBuilder<OutboxWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .build()
            // KEEP: one waiting job is enough, it sends everything queued by the time it runs.
            WorkManager.getInstance(context).enqueueUniqueWork("outbox", ExistingWorkPolicy.KEEP, request)
        }
    }
}
