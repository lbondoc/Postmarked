package io.github.lbondoc.postmarked.data

import kotlin.test.Test
import kotlin.test.assertEquals

class ShareLinkTest {
    @Test
    fun aShareLinkRoundTripsThroughTheAddress() {
        val token = "AbCdEfGhIjKlMnOpQrSt-_"
        assertEquals("https://lbondoc.github.io/Postmarked/#/s/$token", shareLink(token))
        assertEquals(token, shareTokenOf(shareLink(token).substringAfter("Postmarked/")))
    }

    @Test
    fun otherAddressesAreNotShareLinks() {
        for (hash in listOf("", "#/", "#/s/", "#/s/short", "#/s/AbCdEfGhIjKlMnOpQrStUvW", "#/s/AbCdEfGhIjKlMnOpQrSt!!", "#/join/AbCdEfGhIjKlMnOpQrStUv")) {
            assertEquals(null, shareTokenOf(hash), hash)
        }
    }
}
