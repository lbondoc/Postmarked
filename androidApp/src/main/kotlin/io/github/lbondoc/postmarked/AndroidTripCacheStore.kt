package io.github.lbondoc.postmarked

import android.content.Context
import android.util.AtomicFile
import io.github.lbondoc.postmarked.data.TripCacheStore
import java.io.File

/** The offline copies as JSON files in the app's own storage, written atomically so a crash can't half-write one. */
class AndroidTripCacheStore(context: Context) : TripCacheStore {
    private val dir = File(context.filesDir, "offline")

    private fun file(key: String) = AtomicFile(File(dir, "$key.json"))

    override fun read(key: String): String? = runCatching { file(key).readFully().decodeToString() }.getOrNull()

    override fun write(key: String, value: String) {
        dir.mkdirs()
        val target = file(key)
        val out = target.startWrite()
        try {
            out.write(value.encodeToByteArray())
            target.finishWrite(out)
        } catch (e: Exception) {
            target.failWrite(out)
            throw e
        }
    }

    override fun clear() {
        dir.deleteRecursively()
    }
}
