package com.benomad.rncdemo.data

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** The three formats accepted by `startCircuitGuidance`, with their exact key in `params`. */
enum class CircuitFormat(val key: String) {
    TRACE_ROUTE("traceRoute"),
    FLEEX("fleex"),
    BENAV4("benav4"),
}

/**
 * A circuit ready to send: `root` is the `{"waypoints": [...]}` object to put
 * under the format's key in `params`.
 */
data class CircuitPayload(
    val format: CircuitFormat,
    val root: JSONObject,
    val waypointCount: Int,
    /** Taken from the traceroute response when the file contains one. */
    val startUTurnThreshold: Int? = null,
    /**
     * Circuit length in meters: the traceroute response's length if the file provides
     * it, otherwise the sum of straight-line distances between waypoints (slightly short).
     */
    val lengthMeters: Double = 0.0,
) {
    /**
     * Position on the circuit, in %, from the remaining distance reported by BeNav
     * (`onNavigationProgressChanged.distanceToDest`, measured up to `stopPercentage`).
     * BeNav only publishes the circuit % when leaving and rejoining it: in between,
     * it is derived from this distance.
     */
    fun percentageAt(distanceToDest: Double, startPercentage: Double, stopPercentage: Double): Double? {
        if (lengthMeters <= 0.0) return null
        return (stopPercentage - distanceToDest / lengthMeters * 100.0).coerceIn(startPercentage, stopPercentage)
    }
}

class CircuitParseException(message: String) : Exception(message)

/**
 * Recognizes a circuit in a JSON file, whatever its original shape, so that a
 * demo can start from a customer's data as is:
 *
 * - a complete `startCircuitGuidance` command, or just its `params`;
 * - a BeMap traceroute response (`routingRoutes[0].waypoints`, WAYPOINTS option);
 * - a Fleex response (`routeData.waypoints`);
 * - a BeNav4 `startCircuitNav` command (`params[0]`), coordinates converted to degrees;
 * - a `{"waypoints": [...]}` object or a bare array of waypoints.
 *
 * The format of a waypoint array is read from its first element:
 * `coordinate` → traceRoute, `longitude`/`latitude` → fleex, `mPos` → benav4.
 *
 * Only the `waypoints` array is kept: the CircuitNav documentation asks not to
 * send the rest of a response, to limit BeNav's memory usage.
 */
object CircuitParser {

    fun parse(text: String): CircuitPayload {
        val value = try {
            JSONTokener(text.trim().removePrefix("﻿")).nextValue()
        } catch (e: Exception) {
            throw CircuitParseException("The file is not valid JSON (${e.message})")
        }
        return when (value) {
            is JSONArray -> fromWaypoints(value, null, null)
            is JSONObject -> fromObject(value)
            else -> throw CircuitParseException("The file contains neither a JSON object nor a JSON array")
        }
    }

    private fun fromObject(obj: JSONObject): CircuitPayload {
        // Complete RNC command: keep only its params.
        if (obj.optString("method") == "startCircuitGuidance") {
            val params = obj.optJSONObject("params")
                ?: throw CircuitParseException("startCircuitGuidance command without params")
            return fromParams(params)
        }
        if (obj.has("requestFormat")) return fromParams(obj)

        // BeNav4 command: params = [waypoints, ...positional parameters].
        if (obj.optString("method") == "startCircuitNav") {
            val waypoints = obj.optJSONArray("params")?.optJSONArray(0)
                ?: throw CircuitParseException("startCircuitNav command without a waypoints array")
            return fromWaypoints(waypoints, CircuitFormat.BENAV4, null)
        }
        obj.optJSONObject("startCircuitNavCommand")?.let { return fromObject(it) }

        obj.optJSONArray("routingRoutes")?.let { routes ->
            val route = routes.optJSONObject(0)
                ?: throw CircuitParseException("Traceroute response without a route")
            val waypoints = route.optJSONArray("waypoints")
                ?: throw CircuitParseException(
                    "Traceroute response without waypoints: run the request again with the WAYPOINTS option"
                )
            val threshold = if (route.has("startUTurnThreshold")) route.optInt("startUTurnThreshold") else null
            val length = route.optDouble("length").takeIf { it > 0.0 }
            return fromWaypoints(waypoints, CircuitFormat.TRACE_ROUTE, threshold, length)
        }
        obj.optJSONObject("routeData")?.optJSONArray("waypoints")?.let {
            return fromWaypoints(it, CircuitFormat.FLEEX, null)
        }
        obj.optJSONArray("waypoints")?.let { return fromWaypoints(it, null, null) }

        throw CircuitParseException("No waypoints array found in the file")
    }

    private fun fromParams(params: JSONObject): CircuitPayload {
        val requested = params.optString("requestFormat")
        val format = CircuitFormat.values().firstOrNull { it.key.equals(requested, ignoreCase = true) }
            ?: throw CircuitParseException("Unknown requestFormat: \"$requested\" (expected traceRoute, fleex or benav4)")
        // The object's key may use a different case than requestFormat ("traceroute").
        val rootKey = params.keys().asSequence().firstOrNull { it.equals(format.key, ignoreCase = true) }
        val waypoints = rootKey?.let { params.optJSONObject(it)?.optJSONArray("waypoints") }
            ?: throw CircuitParseException("params.${format.key}.waypoints is missing")
        val threshold = if (params.has("startUTurnThreshold")) params.optInt("startUTurnThreshold") else null
        return fromWaypoints(waypoints, format, threshold)
    }

    private fun fromWaypoints(
        waypoints: JSONArray,
        known: CircuitFormat?,
        threshold: Int?,
        length: Double? = null,
    ): CircuitPayload {
        if (waypoints.length() < 2) {
            throw CircuitParseException("A circuit needs at least 2 waypoints (${waypoints.length()} found)")
        }
        val first = waypoints.optJSONObject(0)
            ?: throw CircuitParseException("Waypoints must be JSON objects")
        val format = known ?: detect(first)
            ?: throw CircuitParseException("Unrecognized waypoint format (no coordinate, longitude/latitude or mPos)")
        val array = if (format == CircuitFormat.BENAV4) benav4InDegrees(waypoints) else waypoints
        return CircuitPayload(
            format, JSONObject().put("waypoints", array), array.length(), threshold,
            length ?: crowFlyLength(array, format),
        )
    }

    private fun crowFlyLength(waypoints: JSONArray, format: CircuitFormat): Double {
        val points = (0 until waypoints.length()).mapNotNull { i ->
            val wp = waypoints.optJSONObject(i) ?: return@mapNotNull null
            when (format) {
                CircuitFormat.TRACE_ROUTE -> wp.optJSONObject("coordinate")?.let { it.optDouble("lon") to it.optDouble("lat") }
                CircuitFormat.FLEEX -> wp.optDouble("longitude") to wp.optDouble("latitude")
                CircuitFormat.BENAV4 -> wp.optJSONObject("mPos")?.let { it.optDouble("mX") to it.optDouble("mY") }
            }
        }.filter { (lon, lat) -> !lon.isNaN() && !lat.isNaN() }
        return points.zipWithNext { (lon1, lat1), (lon2, lat2) ->
            val x = Math.toRadians(lon2 - lon1) * kotlin.math.cos(Math.toRadians((lat1 + lat2) / 2))
            val y = Math.toRadians(lat2 - lat1)
            6_371_000.0 * kotlin.math.hypot(x, y)
        }.sum()
    }

    private fun detect(waypoint: JSONObject): CircuitFormat? = when {
        waypoint.has("coordinate") -> CircuitFormat.TRACE_ROUTE
        waypoint.has("longitude") && waypoint.has("latitude") -> CircuitFormat.FLEEX
        waypoint.has("mPos") -> CircuitFormat.BENAV4
        else -> null
    }

    /**
     * BeNav4 expressed `mPos` in millionths of a degree (`mX: 2014107`); BeNav5's
     * benav4 format expects degrees (`mX: 2.014107`). A value outside
     * [-180, 180] cannot be a degree, so it is converted.
     */
    private fun benav4InDegrees(waypoints: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until waypoints.length()) {
            val wp = JSONObject(waypoints.getJSONObject(i).toString())
            wp.optJSONObject("mPos")?.let { pos ->
                for (axis in listOf("mX", "mY")) {
                    val v = pos.optDouble(axis)
                    if (!v.isNaN() && kotlin.math.abs(v) > 180.0) pos.put(axis, v / 1_000_000.0)
                }
            }
            out.put(wp)
        }
        return out
    }
}
