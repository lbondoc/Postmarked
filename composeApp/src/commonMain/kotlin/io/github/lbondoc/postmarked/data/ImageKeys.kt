package io.github.lbondoc.postmarked.data

private const val SIGNED_PREFIX = "/object/sign/media/"

/**
 * The Storage path inside a signed link to the media bucket ("<trip>/<media>/thumb.jpg", the same as
 * [Media.path]), or null for any other link. Signed links carry a new token every time they are made, so the
 * image caches key on this instead: what was cached stays usable after the link expires (SPEC §4).
 */
fun storageKey(url: String): String? {
    val at = url.indexOf(SIGNED_PREFIX).takeIf { it >= 0 } ?: return null
    return url.substring(at + SIGNED_PREFIX.length).substringBefore('?').substringBefore('#').ifEmpty { null }
}
