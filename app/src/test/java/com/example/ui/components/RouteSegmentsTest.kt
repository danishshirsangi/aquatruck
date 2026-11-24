package com.example.ui.components

import com.example.data.model.LocationPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteSegmentsTest {
    private fun p(truck: Int, session: String, t: Long, lat: Double = 15.0 + t / 1e6) =
        LocationPoint(truckId = truck, latitude = lat, longitude = 74.0, speedKmh = 0f, timestamp = t, sessionId = session)

    @Test
    fun sameTruckDifferentShiftsAreNeverJoined() {
        val shiftA = listOf(p(1, "A", 1000), p(1, "A", 2000), p(1, "A", 3000))
        val shiftB = listOf(p(1, "B", 900_000), p(1, "B", 901_000))
        val routes = buildRouteSegments(shiftB + shiftA) // deliberately unordered input
        assertEquals(2, routes.size)
        routes.forEach { route -> assertEquals(1, route.map { it.sessionId }.distinct().size) }
        val a = routes.first { it.first().sessionId == "A" }
        assertEquals(listOf(1000L, 2000L, 3000L), a.map { it.timestamp })
    }

    @Test
    fun differentTrucksAreSeparateRoutes() {
        val routes = buildRouteSegments(listOf(p(1, "A", 1), p(2, "A", 2), p(1, "A", 3)))
        assertEquals(2, routes.size)
        routes.forEach { assertEquals(1, it.map { pt -> pt.truckId }.distinct().size) }
    }

    @Test
    fun lateSyncedPointKeepsItsOwnShift() {
        // A point of shift A that reaches the manager after shift B began must still belong to A.
        val routes = buildRouteSegments(listOf(p(1, "B", 500_000), p(1, "A", 1000), p(1, "B", 501_000), p(1, "A", 2000)))
        assertEquals(setOf(listOf(1000L, 2000L), listOf(500_000L, 501_000L)), routes.map { r -> r.map { it.timestamp } }.toSet())
    }

    @Test
    fun legacyPointsWithoutSessionAreSplitOnTimeGap() {
        val gap = LEGACY_ROUTE_GAP_MS + 1
        val routes = buildRouteSegments(listOf(p(1, "", 0), p(1, "", 20_000), p(1, "", 20_000 + gap), p(1, "", 40_000 + gap)))
        assertEquals(2, routes.size)
    }

    @Test
    fun legacyAndSessionPointsNeverMix() {
        val routes = buildRouteSegments(listOf(p(1, "", 0), p(1, "A", 1000), p(1, "", 2000)))
        assertEquals(2, routes.size)
        assertTrue(routes.any { r -> r.all { it.sessionId == "A" } })
        assertTrue(routes.any { r -> r.all { it.sessionId.isBlank() } })
    }

    @Test
    fun zeroCoordinatesAreIgnored() {
        assertTrue(buildRouteSegments(listOf(LocationPoint(truckId = 1, latitude = 0.0, longitude = 0.0, speedKmh = 0f, sessionId = "A"))).isEmpty())
    }
}
