package com.benomad.rncdemo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RncMessagesTest {

    @Test
    fun `startGuidance sends 19 waypoints and the 20th as destination`() {
        val msg = RncMessages.startGuidance("7", DemoData.points, optimize = false)
        val params = msg.getJSONObject("params")
        assertEquals("startGuidance", msg.getString("method"))
        assertEquals("7", msg.getString("id"))
        assertEquals(19, params.getJSONArray("waypoints").length())
        assertEquals("dropoff_20", params.getJSONObject("destination").getString("id"))
        assertEquals("dropoff_01", params.getJSONArray("waypoints").getJSONObject(0).getString("id"))
        assertFalse(params.has("optimizeRoute"))
    }

    @Test
    fun `optimize adds optimizeRoute BEST`() {
        val params = RncMessages.startGuidance("1", DemoData.points, optimize = true).getJSONObject("params")
        assertEquals("BEST", params.getString("optimizeRoute"))
    }

    @Test
    fun `the 20 drop-off points have unique ids and are in central Nice`() {
        assertEquals(20, DemoData.points.size)
        assertEquals(20, DemoData.points.map { it.id }.toSet().size)
        DemoData.points.forEach {
            assertTrue(it.label, it.lat in 43.69..43.71)
            assertTrue(it.label, it.lon in 7.25..7.29)
        }
    }

    @Test
    fun `setupNotifications only names notifications accepted by BeNav`() {
        val names = RncMessages.setupNotifications("1").getJSONObject("params").getJSONArray("notifications")
            .let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("name") } }
        val accepted = setOf(
            "onNavigationProgressChanged", "onViaPointReached", "onDestinationReached", "onGuidanceStopped",
            "onVehicleProfileAlert", "onVehicleStatusChanged", "onAppStatusChanged",
        )
        assertTrue(names.isNotEmpty())
        assertTrue(names.all { it in accepted })
    }

    @Test
    fun `startCircuitGuidance puts the waypoints under the format key`() {
        val circuit = CircuitParser.parse(File("src/main/assets/${DemoData.CIRCUIT_ASSET}").readText())
        val params = RncMessages.startCircuitGuidance("3", circuit, CircuitOptions(startPercentage = 10.0))
            .getJSONObject("params")
        assertEquals("traceRoute", params.getString("requestFormat"))
        assertEquals(43, params.getJSONObject("traceRoute").getJSONArray("waypoints").length())
        assertEquals(10.0, params.getDouble("startPercentage"), 0.0)
        assertEquals(3000, params.getInt("startUTurnThreshold"))
        assertFalse(params.getBoolean("freeDriveAuto"))
    }

    @Test
    fun `showPoi sends width and height together`() {
        val poi = MapPoi("dropoff_01", 43.6, 7.1, "1. Test", "https://example.com/dropoff.png", 64, 48)
        val p = RncMessages.showPoi("1", listOf(poi)).getJSONObject("params").getJSONArray("pois").getJSONObject(0)
        assertEquals("https://example.com/dropoff.png", p.getString("iconURL"))
        assertEquals(64, p.getInt("iconWidth"))
        assertEquals(48, p.getInt("iconHeight"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `hidePoi rejects an empty list`() {
        RncMessages.hidePoi("1", emptyList())
    }
}
