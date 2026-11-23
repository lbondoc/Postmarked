package io.github.lbondoc.postmarked.web

import io.github.lbondoc.postmarked.data.TripCacheStore

private const val PREFIX = "postmarked:"

private fun storageGet(key: String): String? = js("(() => { try { return localStorage.getItem(key); } catch (e) { return null; } })()")
private fun storageSet(key: String, value: String): Unit = js("(() => { try { localStorage.setItem(key, value); } catch (e) {} })()")
private fun storageClear(prefix: String): Unit = js(
    """(() => { try {
        Object.keys(localStorage).filter((k) => k.startsWith(prefix)).forEach((k) => localStorage.removeItem(k));
    } catch (e) {} })()""",
)

/**
 * The offline copies in `localStorage` (small JSON only, SPEC §4). A full or blocked storage (private browsing)
 * just means no offline copy: writes are dropped and reads give nothing.
 */
internal object LocalStorageTripCache : TripCacheStore {
    override fun read(key: String): String? = storageGet(PREFIX + key)
    override fun write(key: String, value: String) = storageSet(PREFIX + key, value)
    override fun clear() = storageClear(PREFIX)
}
