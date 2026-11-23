package io.github.lbondoc.postmarked.ui

import io.github.lbondoc.postmarked.domain.geo.LatLng
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouteProjectionTest {
    private val sf = LatLng(37.77, -122.42)
    private val la = LatLng(34.05, -118.24)
    private val vegas = LatLng(36.17, -115.14)

    @Test
    fun everyPointLandsInsideThePaddedBox() {
        val at = routeProjection(listOf(sf, la, vegas), 400f, 300f, 40f)
        for (p in listOf(sf, la, vegas).map(at)) {
            assertTrue(p.x in 40f - 0.01f..360.01f && p.y in 40f - 0.01f..260.01f, "$p")
        }
    }

    @Test
    fun northIsUpAndEastIsRight() {
        val at = routeProjection(listOf(sf, la, vegas), 400f, 300f, 40f)
        assertTrue(at(sf).y < at(la).y) // SF is north of LA
        assertTrue(at(vegas).x > at(sf).x) // Vegas is east of SF
    }

    @Test
    fun theWiderSpanFillsItsSideAndTheDrawingIsCentred() {
        val at = routeProjection(listOf(sf, vegas), 400f, 400f, 40f)
        // SF to Vegas is wider than tall, so it spans the whole padded width.
        assertEquals(40f, at(sf).x, 0.01f)
        assertEquals(360f, at(vegas).x, 0.01f)
        assertEquals(200f, (at(sf).y + at(vegas).y) / 2, 0.01f)
    }

    @Test
    fun aSingleStopSitsInTheMiddle() {
        val at = routeProjection(listOf(sf), 400f, 300f, 40f)
        assertTrue(abs(at(sf).x - 200f) < 0.01f && abs(at(sf).y - 150f) < 0.01f)
    }
}
