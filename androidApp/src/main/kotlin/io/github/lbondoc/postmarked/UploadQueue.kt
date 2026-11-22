package io.github.lbondoc.postmarked

import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import androidx.core.content.edit
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import io.github.lbondoc.postmarked.data.DeviceMedia
import io.github.lbondoc.postmarked.data.QueuedUpload
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * This phone's upload queue, saved to a file so it survives the app being closed or the
 * phone restarting. UploadWorker works through it in the background.
 * shortcut: a JSON file read whole into memory, move it into the local database once there is one (the sync milestone).
 */
object UploadQueue {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var file: AtomicFile
    private lateinit var prefs: SharedPreferences

    val items = MutableStateFlow<List<QueuedUpload>>(emptyList())
    val mobileData = MutableStateFlow(false)

    /** Called once from PostmarkedApp, before any screen or background upload runs. */
    fun init(context: Context) {
        // AtomicFile writes a new copy and swaps it in, so a crash mid-save can't corrupt the queue.
        file = AtomicFile(File(context.filesDir, "uploads.json"))
        items.value = runCatching { json.decodeFromString<List<QueuedUpload>>(file.readFully().decodeToString()) }
            .getOrDefault(emptyList()) // no file yet on first run
        prefs = context.getSharedPreferences("uploads", Context.MODE_PRIVATE)
        mobileData.value = prefs.getBoolean("mobile_data", false)
    }

    suspend fun update(change: (List<QueuedUpload>) -> List<QueuedUpload>) = lock.withLock {
        val next = change(items.value)
        val out = file.startWrite()
        try {
            out.write(json.encodeToString(next).encodeToByteArray())
            file.finishWrite(out)
        } catch (e: IOException) {
            file.failWrite(out)
            throw e
        }
        items.value = next
    }

    suspend fun save(entry: QueuedUpload) = update { list -> list.map { if (it.mediaId == entry.mediaId) entry else it } }

    fun add(context: Context, tripId: String, picked: List<DeviceMedia>) {
        scope.launch {
            update { list ->
                // An item that failed before and is picked again gets a fresh start.
                val retried = picked.map { it.uri }.toSet()
                list.filterNot { it.tripId == tripId && it.problem != null && it.item.uri in retried } +
                    picked.map { QueuedUpload(UUID.randomUUID().toString(), tripId, it) }
            }
            UploadWork.start(context)
        }
    }

    fun allowMobileData(context: Context, allowed: Boolean) {
        prefs.edit { putBoolean("mobile_data", allowed) }
        mobileData.value = allowed
        UploadWork.start(context, restart = true) // so the new network rule applies to what is already queued
    }

    /** Signing out drops this phone's queue: it belongs to the account that filled it. */
    fun clear(context: Context) {
        scope.launch {
            WorkManager.getInstance(context).cancelUniqueWork(UploadWork.NAME)
            update { emptyList() }
            UploadWorker.scratchRoot(context).deleteRecursively()
        }
    }
}

object UploadWork {
    const val NAME = "uploads"

    fun start(context: Context, restart: Boolean = false) {
        val network = if (UploadQueue.mobileData.value) NetworkType.CONNECTED else NetworkType.UNMETERED
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // APPEND: if a run is going, this one starts after it, so items added meanwhile are never stranded.
        val policy = if (restart) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE
        WorkManager.getInstance(context).enqueueUniqueWork(NAME, policy, request)
    }
}
