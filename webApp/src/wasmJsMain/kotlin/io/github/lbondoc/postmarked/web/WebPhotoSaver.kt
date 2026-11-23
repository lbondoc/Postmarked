package io.github.lbondoc.postmarked.web

import io.github.lbondoc.postmarked.data.PhotoSaver
import io.github.lbondoc.postmarked.data.SavedFile
import io.github.lbondoc.postmarked.data.extensionFor
import kotlinx.coroutines.await

/** On the web a saved photo is a download; sharing uses Web Share where the browser has it, else downloads. */
internal object WebPhotoSaver : PhotoSaver {
    override suspend fun save(file: SavedFile) {
        val blob = fetchBlob(file.url).await<JsAny>()
        downloadBlob(blob, file.name + extensionFor(blobType(blob)))
    }

    override suspend fun share(file: SavedFile) {
        val blob = fetchBlob(file.url).await<JsAny>()
        val name = file.name + extensionFor(blobType(blob))
        if (!shareBlob(blob, name)) downloadBlob(blob, name)
    }
}
