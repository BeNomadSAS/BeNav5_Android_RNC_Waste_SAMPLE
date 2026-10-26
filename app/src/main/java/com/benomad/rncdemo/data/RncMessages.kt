package com.benomad.rncdemo.data

import org.json.JSONArray
import org.json.JSONObject

/** Options shared by all `startCircuitGuidance` formats (see the CircuitNav documentation). */
data class CircuitOptions(
    val startPercentage: Double = 0.0,
    val stopPercentage: Double = 100.0,
    /** false: BeNav reroutes back to the circuit; true: free drive, no rerouting. */
    val freeDriveAuto: Boolean = false,
    /**
     * Automatic guidance resume in free drive: only if the truck rejoins the circuit
     * within this distance (along the circuit) of the point where it left. A short value
     * makes BeNav stay in free drive when the truck skips a few streets: with the demo
     * circuit, leaving at 23% and rejoining at 48% is ~570 m further on.
     */
    val freeDriveAutoResumeDistance: Int = 3000,
    val hidePassedCircuit: Boolean = true,
    /** 0: the default behavior is chosen without showing the popup. */
    val reroutingPopupTimeout: Int = 20,
    /** Blocked section [blockStartPercentage, startPercentage]; 100 = disabled. */
    val blockStartPercentage: Double = 100.0,
)

/** Point shown on BeNav's map by `showPoi`. */
data class MapPoi(
    val id: String,
    val lat: Double,
    val lon: Double,
    val label: String,
    /** `https://` or a `content://` URI granted to BeNav; never `file://`. */
    val iconUrl: String,
    /** Display size on the map, 1 to 512. */
    val iconWidth: Int,
    val iconHeight: Int,
)

/**
 * Builds the RNC JSON-RPC commands sent to BeNav5.
 *
 * Pure Kotlin + org.json, no Android dependency: testable on the JVM. Field names
 * come from the BeNav5 RNC documentation and the BeNav5 RNC CircuitNav
 * documentation; do not invent any, BeNav would ignore an unknown field or
 * reject the command with `parse.error`.
 */
object RncMessages {

    fun command(id: String, method: String, params: JSONObject? = null): JSONObject =
        JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        }

    /**
     * Guidance notifications are disabled by default and the subscription does not
     * survive a BeNav restart: it is resent before each guidance.
     * CircuitNav notifications (`onCircuitLeft`, `onCircuitJoined`,
     * `onCanJoinCircuit`) are always active and cannot be subscribed to; naming them
     * here would get the whole command rejected. `onAppStatusChanged` is on by default,
     * which is what lets the app subscribe again when BeNav restarts.
     */
    fun setupNotifications(id: String): JSONObject {
        fun notif(name: String, extra: JSONObject.() -> Unit = {}) = JSONObject().apply {
            put("name", name)
            put("params", JSONObject().apply { put("enabled", true); extra() })
        }
        val notifications = JSONArray()
            .put(notif("onNavigationProgressChanged") {
                put("minPeriod", 30) // minimum enforced by BeNav
                put("waypointsInfo", false)
            })
            .put(notif("onViaPointReached"))
            .put(notif("onDestinationReached"))
            .put(notif("onGuidanceStopped"))
        return command(id, "setupNotifications", JSONObject().put("notifications", notifications))
    }

    /**
     * Point-to-point guidance: every point but the last in `waypoints`, the
     * last one in `destination`, start = current vehicle position.
     *
     * `optimizeRoute` absent = fixed order; present, BeNav reorders the
     * waypoints (TO_FIXED_STOP_TRIP mode since the destination is fixed).
     */
    fun startGuidance(id: String, points: List<GuidancePoint>, optimize: Boolean): JSONObject {
        require(points.isNotEmpty()) { "At least one point" }
        fun point(p: GuidancePoint) = JSONObject().apply {
            put("id", p.id)
            put("lat", p.lat)
            put("lon", p.lon)
        }
        val waypoints = JSONArray()
        points.dropLast(1).forEach { waypoints.put(point(it)) }
        val params = JSONObject().apply {
            put("destination", point(points.last()))
            if (waypoints.length() > 0) put("waypoints", waypoints)
            if (optimize) put("optimizeRoute", "BEST")
            put("avoidUTurns", true)
            put("continueIfMapMatchFailed", true)
            put("alternativeRoutes", false)
            put("enableRoutePreview", false)
        }
        return command(id, "startGuidance", params)
    }

    fun startCircuitGuidance(id: String, circuit: CircuitPayload, options: CircuitOptions): JSONObject {
        val params = JSONObject().apply {
            put("requestFormat", circuit.format.key)
            put(circuit.format.key, circuit.root)
            put("startUTurnThreshold", circuit.startUTurnThreshold ?: 3000)
            put("startPercentage", options.startPercentage)
            put("stopPercentage", options.stopPercentage)
            put("blockStartPercentage", options.blockStartPercentage)
            put("freeDriveAuto", options.freeDriveAuto)
            put("freeDriveAutoResumeDistance", options.freeDriveAutoResumeDistance)
            put("hidePassedCircuit", options.hidePassedCircuit)
            put("reroutingPopupTimeout", options.reroutingPopupTimeout)
        }
        return command(id, "startCircuitGuidance", params)
    }

    fun showPoi(id: String, pois: List<MapPoi>): JSONObject {
        val array = JSONArray()
        pois.forEach { poi ->
            array.put(JSONObject().apply {
                put("id", poi.id)
                put("lat", poi.lat)
                put("lon", poi.lon)
                put("description", poi.label)
                put("iconURL", poi.iconUrl)
                // Both or neither: a single dimension fails validation.
                put("iconWidth", poi.iconWidth)
                put("iconHeight", poi.iconHeight)
            })
        }
        return command(id, "showPoi", JSONObject().put("pois", array))
    }

    /** `ids` must never be empty: BeNav replies `parse.error`. */
    fun hidePoi(id: String, poiIds: List<String>): JSONObject {
        require(poiIds.isNotEmpty()) { "hidePoi needs at least one id" }
        return command(id, "hidePoi", JSONObject().put("ids", JSONArray(poiIds)))
    }

    fun hideAllPois(id: String) = command(id, "hideAllPois", JSONObject())
    fun joinCircuit(id: String) = command(id, "joinCircuit")
    fun stopGuidance(id: String) = command(id, "stopGuidance")
    fun showApp(id: String) = command(id, "showApp")
}
