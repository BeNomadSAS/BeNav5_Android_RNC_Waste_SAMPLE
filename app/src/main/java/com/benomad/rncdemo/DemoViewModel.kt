package com.benomad.rncdemo

import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.benomad.rncdemo.data.CircuitOptions
import com.benomad.rncdemo.data.CircuitParseException
import com.benomad.rncdemo.data.CircuitParser
import com.benomad.rncdemo.data.CircuitPayload
import com.benomad.rncdemo.data.DemoData
import com.benomad.rncdemo.data.GuidancePoint
import com.benomad.rncdemo.data.MapPoi
import com.benomad.rncdemo.data.PoiIcons
import com.benomad.rncdemo.data.RncClient
import com.benomad.rncdemo.data.RncEvent
import com.benomad.rncdemo.data.RncMessages
import com.benomad.rncdemo.data.Transport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Direction { OUT, IN, INFO, ERROR }

data class LogEntry(val time: String, val direction: Direction, val title: String, val json: String)

enum class Scenario(val label: String) { CIRCUIT("Door-to-door collection"), DROP_OFF("Drop-off collection") }

enum class PointStatus { PENDING, REACHED, SKIPPED }

/** Where the truck stands relative to the circuit, according to CircuitNav notifications. */
data class CircuitState(val onCircuit: Boolean, val percentage: Double?)

/**
 * Drives both demos and holds the displayed state. Owns the RNC client, so it
 * survives screen rotations; it is cleared when the activity finishes for good.
 */
class DemoViewModel(app: Application) : AndroidViewModel(app) {

    private val client = RncClient(app, ::onRncEvent)
    private val icons = PoiIcons(app)
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.UK)

    val log = mutableStateListOf<LogEntry>()

    var benavVersion by mutableStateOf<String?>(null); private set
    var activeScenario by mutableStateOf<Scenario?>(null); private set
    var guidanceRunning by mutableStateOf(false); private set
    var lastError by mutableStateOf<String?>(null); private set
    var remaining by mutableStateOf<String?>(null); private set
    /** Between tapping "Start" and BeNav's reply: a second tap would restart everything. */
    var launching by mutableStateOf(false); private set

    // — Door to door —
    var circuit by mutableStateOf<CircuitPayload?>(null); private set
    var circuitLabel by mutableStateOf(DemoData.CIRCUIT_LABEL); private set
    var circuitOptions by mutableStateOf(CircuitOptions())
    var circuitState by mutableStateOf<CircuitState?>(null); private set
    /** Circuit and options actually sent, used to turn the remaining distance into a %. */
    private var runningCircuit: Pair<CircuitPayload, CircuitOptions>? = null
    /** Time (uptime) of the last `onCanJoinCircuit`; BeNav repeats it every second. */
    private var canJoinAt by mutableStateOf(0L)
    var now by mutableStateOf(SystemClock.uptimeMillis()); private set
    val canJoinCircuit get() = guidanceRunning && now - canJoinAt < CAN_JOIN_VALIDITY_MS

    // — Drop-off points —
    var optimizeRoute by mutableStateOf(true)
    var showDropOffOnMap by mutableStateOf(true)
    val pointStatus = mutableStateMapOf<String, PointStatus>()
    /** Ids of the points shown by `showPoi`, to remove them one by one once collected. */
    private val shownPoiIds = mutableSetOf<String>()

    init {
        refreshBenavVersion()
        loadDemoCircuit()
        viewModelScope.launch {
            while (true) { now = SystemClock.uptimeMillis(); delay(500) }
        }
        // Started when "Start" is tapped (app in the foreground), stopped when guidance ends.
        viewModelScope.launch {
            snapshotFlow { launching || guidanceRunning }.distinctUntilChanged().collect { active ->
                if (active) GuidanceKeepAlive.start(app) else GuidanceKeepAlive.stop(app)
            }
        }
    }

    override fun onCleared() {
        // The activity is finishing: nobody left to follow the guidance.
        GuidanceKeepAlive.stop(getApplication())
        client.release()
    }

    fun refreshBenavVersion() {
        val pm = getApplication<Application>().packageManager
        benavVersion = try {
            val info = if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(RncClient.BENAV_PACKAGE, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION") pm.getPackageInfo(RncClient.BENAV_PACKAGE, 0)
            }
            info.versionName
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    // ——— Circuit ———

    fun loadDemoCircuit() {
        val text = getApplication<Application>().assets.open(DemoData.CIRCUIT_ASSET).bufferedReader().use { it.readText() }
        circuit = CircuitParser.parse(text)
        circuitLabel = DemoData.CIRCUIT_LABEL
    }

    fun importCircuit(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = getApplication<Application>().contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() }
                        ?: throw CircuitParseException("Unreadable file")
                    CircuitParser.parse(text)
                }
            }
            result.onSuccess {
                circuit = it
                circuitLabel = "Imported file: ${uri.lastPathSegment?.substringAfterLast('/') ?: "circuit"}"
                info("Circuit imported: ${it.waypointCount} waypoints, ${it.format.key} format")
            }.onFailure {
                error("Import failed: ${it.message}")
            }
        }
    }

    fun startCircuit() {
        val payload = circuit ?: return
        if (launching) return
        prepareGuidance(Scenario.CIRCUIT)
        circuitState = null
        canJoinAt = 0L
        val options = circuitOptions
        runningCircuit = payload to options
        client.send(Transport.ACTIVITY, replyTimeoutMs = RncClient.GUIDANCE_TIMEOUT_MS, onReply = { reply ->
            launching = false
            if (!reply.has("error")) guidanceRunning = true
        }) { RncMessages.startCircuitGuidance(it, payload, options) }
    }

    fun joinCircuit() {
        canJoinAt = 0L
        client.send(Transport.ACTIVITY) { RncMessages.joinCircuit(it) }
    }

    // ——— Drop-off points ———

    fun startDropOff() {
        val points = DemoData.points
        if (launching) return
        prepareGuidance(Scenario.DROP_OFF)
        pointStatus.clear()
        points.forEach { pointStatus[it.id] = PointStatus.PENDING }
        val showOnMap = showDropOffOnMap
        // Two replies: route computed, then guidance started. showPoi is only
        // sent after the second one, otherwise BeNav ignores it.
        client.send(
            Transport.ACTIVITY,
            expectedReplies = 2,
            replyTimeoutMs = RncClient.GUIDANCE_TIMEOUT_MS,
            onReply = { reply ->
                if (reply.has("error")) { launching = false; return@send }
                val result = reply.optJSONObject("result")
                if (result != null && result.length() == 0) {
                    launching = false
                    guidanceRunning = true
                    if (showOnMap) showPois(points)
                }
            },
        ) { RncMessages.startGuidance(it, points, optimizeRoute) }
    }

    private fun showPois(points: List<GuidancePoint>) {
        viewModelScope.launch {
            val pois = withContext(Dispatchers.IO) {
                points.map { p ->
                    val icon = icons.forStream(p.stream)
                    MapPoi(p.id, p.lat, p.lon, "${p.number}. ${p.label} — ${p.stream.label}", icon.url, icon.width, icon.height)
                }
            }
            shownPoiIds.clear()
            shownPoiIds += pois.map { it.id }
            client.send(Transport.SERVICE) { RncMessages.showPoi(it, pois) }
        }
    }

    // ——— Common ———

    /** Start of every guidance: subscription (lost if BeNav restarted) and a clean map. */
    private fun prepareGuidance(scenario: Scenario) {
        client.clearQueue()
        lastError = null
        remaining = null
        guidanceRunning = false
        launching = true
        activeScenario = scenario
        shownPoiIds.clear()
        client.send(Transport.SERVICE) { RncMessages.setupNotifications(it) }
        client.send(Transport.SERVICE) { RncMessages.hideAllPois(it) }
    }

    fun stopGuidance() {
        client.clearQueue()
        launching = false
        client.send(Transport.SERVICE) { RncMessages.stopGuidance(it) }
        client.send(Transport.SERVICE) { RncMessages.hideAllPois(it) }
        endGuidance()
    }

    fun openBeNav() {
        client.send(Transport.ACTIVITY) { RncMessages.showApp(it) }
    }

    fun clearLog() = log.clear()

    private fun endGuidance() {
        guidanceRunning = false
        remaining = null
        canJoinAt = 0L
        shownPoiIds.clear()
    }

    private fun onRncEvent(event: RncEvent) {
        when (event) {
            is RncEvent.Sent -> add(
                Direction.OUT,
                "${event.method} (${event.transport.name.lowercase()}${if (event.attempt > 1) ", attempt ${event.attempt}" else ""})",
                event.json,
            )
            is RncEvent.Reply -> {
                val err = event.message.optJSONObject("error")
                if (err != null) {
                    val code = err.optInt("code")
                    val text = "${event.method}: ${err.optString("message")} ($code)"
                    add(Direction.ERROR, text, event.json)
                    // busy / not ready / not started are retried by the client: not an error to show.
                    if (code !in listOf(RncClient.BUSY, RncClient.NOT_READY, RncClient.NOT_STARTED)) lastError = text
                } else {
                    add(Direction.IN, "${event.method} ✓", event.json)
                }
            }
            is RncEvent.Notification -> onNotification(event)
            is RncEvent.GaveUp -> {
                launching = false
                add(Direction.ERROR, "${event.method} given up: ${event.reason}", event.json)
                lastError = "${event.method}: ${event.reason}"
            }
            is RncEvent.Other -> add(Direction.IN, "Message", event.json)
        }
    }

    private fun onNotification(event: RncEvent.Notification) {
        val p = event.params
        // onCanJoinCircuit arrives every second: not logged, it would drown everything else.
        if (event.method == "onCanJoinCircuit") {
            canJoinAt = SystemClock.uptimeMillis()
            circuitState = CircuitState(onCircuit = false, percentage = p.optDoubleOrNull("percentage"))
            return
        }
        add(Direction.IN, event.method, event.json)
        when (event.method) {
            "onNavigationProgressChanged" -> {
                val dist = p.optDoubleOrNull("distanceToDest")
                val dur = p.optDoubleOrNull("drivingDurationToDest")
                if (dist != null && dur != null) remaining = "${formatDistance(dist)} · ${formatDuration(dur)} left"
                // On the circuit, the remaining distance gives the %; off the circuit it
                // measures the way back, so wait for onCircuitJoined instead.
                val running = runningCircuit
                if (activeScenario == Scenario.CIRCUIT && running != null && dist != null &&
                    p.optString("state") == "ON_ROUTE" && circuitState?.onCircuit != false
                ) {
                    val (payload, options) = running
                    payload.percentageAt(dist, options.startPercentage, options.stopPercentage)
                        ?.let { circuitState = CircuitState(true, it) }
                }
            }
            "onViaPointReached" -> {
                val id = p.optString("id")
                if (id in pointStatus) {
                    pointStatus[id] = if (p.optBoolean("skipped")) PointStatus.SKIPPED else PointStatus.REACHED
                    if (shownPoiIds.remove(id)) client.send(Transport.SERVICE) { RncMessages.hidePoi(it, listOf(id)) }
                }
            }
            "onDestinationReached" -> {
                DemoData.points.last().id.let { id ->
                    if (id in pointStatus) pointStatus[id] = PointStatus.REACHED
                }
                endGuidance()
                if (activeScenario == Scenario.DROP_OFF) client.send(Transport.SERVICE) { RncMessages.hideAllPois(it) }
            }
            "onGuidanceStopped" -> endGuidance()
            "onCircuitLeft" -> circuitState = CircuitState(false, p.optDoubleOrNull("percentage"))
            "onCircuitJoined" -> {
                canJoinAt = 0L
                circuitState = CircuitState(true, p.optDoubleOrNull("percentage"))
            }
            "onAppStatusChanged" -> if (p.optString("event") == "startupComplete") {
                // BeNav restarted: the notification subscription is lost.
                client.send(Transport.SERVICE) { RncMessages.setupNotifications(it) }
            }
        }
    }

    private fun info(text: String) = add(Direction.INFO, text, "")

    private fun error(text: String) {
        add(Direction.ERROR, text, "")
        lastError = text
    }

    private fun add(direction: Direction, title: String, json: String) {
        log.add(0, LogEntry(clock.format(Date()), direction, title, json))
        if (log.size > MAX_LOG) log.removeRange(MAX_LOG, log.size)
    }

    companion object {
        private const val MAX_LOG = 300
        private const val CAN_JOIN_VALIDITY_MS = 3_000L

        private fun JSONObject.optDoubleOrNull(name: String): Double? =
            optDouble(name).takeUnless { it.isNaN() }

        fun formatDistance(m: Double) = if (m >= 1000) String.format(Locale.UK, "%.1f km", m / 1000) else "${m.toInt()} m"

        fun formatDuration(s: Double): String {
            val min = (s / 60).toInt()
            return if (min >= 60) "${min / 60} h ${"%02d".format(min % 60)}" else "$min min"
        }
    }
}
