package io.github.lbondoc.postmarked.ui

import coil3.ImageLoader
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import io.github.lbondoc.postmarked.data.storageKey

/** Keys every signed Storage link in the memory and disk caches by its file path, so a new link hits the cache. */
internal object StoragePathKeys : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val key = (request.data as? String)?.let(::storageKey) ?: return chain.proceed()
        val keyed = request.newBuilder()
            .memoryCacheKey(request.memoryCacheKey ?: key)
            .diskCacheKey(request.diskCacheKey ?: key)
            .build()
        return chain.withRequest(keyed).proceed()
    }
}

/** The app's image loader: Coil's defaults (and its network fetcher) plus [StoragePathKeys]. */
internal fun imageLoader(context: coil3.PlatformContext): ImageLoader =
    ImageLoader.Builder(context).components { add(StoragePathKeys) }.build()
