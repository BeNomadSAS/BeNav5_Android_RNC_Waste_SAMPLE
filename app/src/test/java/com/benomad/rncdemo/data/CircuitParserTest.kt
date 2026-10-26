package com.benomad.rncdemo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class CircuitParserTest {

    private val trWaypoint = """{"coordinate":{"lon":7.1,"lat":43.6},"angle":10}"""
    private val fleexWaypoint = """{"longitude":7.1,"latitude":43.6,"commands":[]}"""

    @Test
    fun `demo asset`() {
        val c = CircuitParser.parse(File("src/main/assets/${DemoData.CIRCUIT_ASSET}").readText())
        assertEquals(CircuitFormat.TRACE_ROUTE, c.format)
        assertEquals(43, c.waypointCount)
        assertEquals(2323.0, c.lengthMeters, 0.0)
        assertEquals(3000, c.startUTurnThreshold)
        assertFalse(c.root.has("_comment"))
    }

    @Test
    fun `percentage derived from remaining distance`() {
        val c = CircuitParser.parse(File("src/main/assets/${DemoData.CIRCUIT_ASSET}").readText())
        assertEquals(0.0, c.percentageAt(2330.0, 0.0, 100.0)!!, 0.0) // slightly before the start
        assertEquals(50.0, c.percentageAt(1161.5, 0.0, 100.0)!!, 1e-9)
        assertEquals(100.0, c.percentageAt(0.0, 0.0, 100.0)!!, 0.0)
        // End moved to 60%: the remaining distance is measured up to there.
        assertEquals(50.0, c.percentageAt(232.3, 0.0, 60.0)!!, 1e-9)
    }

    @Test
    fun `without a given length the straight-line distance is used`() {
        val c = CircuitParser.parse(
            """{"waypoints":[{"coordinate":{"lon":7.0,"lat":43.0}},{"coordinate":{"lon":7.0,"lat":43.01}}]}"""
        )
        assertEquals(1112.0, c.lengthMeters, 1.0)
    }

    @Test
    fun `complete startCircuitGuidance command`() {
        val c = CircuitParser.parse(
            """{"jsonrpc":"2.0","id":"1","method":"startCircuitGuidance","params":{"requestFormat":"fleex","fleex":{"waypoints":[$fleexWaypoint,$fleexWaypoint]},"startUTurnThreshold":1500}}"""
        )
        assertEquals(CircuitFormat.FLEEX, c.format)
        assertEquals(1500, c.startUTurnThreshold)
    }

    @Test
    fun `BeMap traceroute response`() {
        val c = CircuitParser.parse(
            """{"routingRoutes":[{"startUTurnThreshold":2500,"waypoints":[$trWaypoint,$trWaypoint,$trWaypoint],"polyline":"xyz"}]}"""
        )
        assertEquals(CircuitFormat.TRACE_ROUTE, c.format)
        assertEquals(3, c.waypointCount)
        assertEquals(2500, c.startUTurnThreshold)
    }

    @Test
    fun `fleex response`() {
        val c = CircuitParser.parse("""{"routeData":{"waypoints":[$fleexWaypoint,$fleexWaypoint]}}""")
        assertEquals(CircuitFormat.FLEEX, c.format)
    }

    @Test
    fun `bare array detected from its first element`() {
        assertEquals(CircuitFormat.TRACE_ROUTE, CircuitParser.parse("[$trWaypoint,$trWaypoint]").format)
        assertEquals(CircuitFormat.FLEEX, CircuitParser.parse("""{"waypoints":[$fleexWaypoint,$fleexWaypoint]}""").format)
    }

    @Test
    fun `benav4 in millionths converted to degrees`() {
        val c = CircuitParser.parse(
            """{"method":"startCircuitNav","params":[[{"mPos":{"mX":2014107,"mY":48123456}},{"mPos":{"mX":2.5,"mY":48.5}}],1]}"""
        )
        assertEquals(CircuitFormat.BENAV4, c.format)
        val wps = c.root.getJSONArray("waypoints")
        assertEquals(2.014107, wps.getJSONObject(0).getJSONObject("mPos").getDouble("mX"), 1e-9)
        assertEquals(48.123456, wps.getJSONObject(0).getJSONObject("mPos").getDouble("mY"), 1e-9)
        assertEquals(2.5, wps.getJSONObject(1).getJSONObject("mPos").getDouble("mX"), 1e-9)
    }

    @Test(expected = CircuitParseException::class)
    fun `traceroute without WAYPOINTS option rejected`() {
        CircuitParser.parse("""{"routingRoutes":[{"polyline":"xyz"}]}""")
    }

    @Test(expected = CircuitParseException::class)
    fun `invalid JSON rejected`() {
        CircuitParser.parse("not json")
    }
}
