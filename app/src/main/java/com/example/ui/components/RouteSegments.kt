package com.example.ui.components

import com.example.data.model.LocationPoint

/**
 * Legacy history only (points that carry no sessionId, e.g. recorded by an older build and not
 * matched to any attendance window): split into separate routes when two consecutive points are
 * further apart in time than this. New data never relies on this; it uses sessionId.
 */
internal const val LEGACY_ROUTE_GAP_MS = 15 * 60_000L

/**
 * Builds the routes the manager map draws. One route = the points of ONE truck in ONE shift,
 * sorted by time. Points of different shifts are never placed in the same route, so no line can
 * ever be drawn from the end of one shift to the start of another.
 *
 *  - points with a sessionId are grouped by (truckId, sessionId);
 *  - points without one (old data) are grouped per truck and split on [LEGACY_ROUTE_GAP_MS].
 */
internal fun buildRouteSegments(points: List<LocationPoint>): List<List<LocationPoint>> {
    val valid = points.filter { it.latitude != 0.0 || it.longitude != 0.0 }
    val routes = mutableListOf<List<LocationPoint>>()

    valid.filter { it.sessionId.isNotBlank() }
        .groupBy { it.truckId to it.sessionId }
        .values
        .forEach { routes.add(it.sortedBy { p -> p.timestamp }) }

    valid.filter { it.sessionId.isBlank() }
        .groupBy { it.truckId }
        .values
        .forEach { truckPoints ->
            var current = mutableListOf<LocationPoint>()
            truckPoints.sortedBy { it.timestamp }.forEach { point ->
                if (current.isNotEmpty() && point.timestamp - current.last().timestamp > LEGACY_ROUTE_GAP_MS) {
                    routes.add(current)
                    current = mutableListOf()
                }
                current.add(point)
            }
            if (current.isNotEmpty()) routes.add(current)
        }

    return routes
}
