package com.benomad.rncdemo.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.benomad.rncdemo.DemoViewModel
import com.benomad.rncdemo.Direction
import com.benomad.rncdemo.LogEntry
import com.benomad.rncdemo.PointStatus
import com.benomad.rncdemo.Scenario
import com.benomad.rncdemo.data.DemoData
import kotlin.math.roundToInt

private val Navy = Color(0xFF0B3D5C)
private val Green = Color(0xFF2E7D32)

private val colors = lightColorScheme(
    primary = Navy,
    secondary = Green,
    primaryContainer = Color(0xFFD6E6F0),
)

@Composable
fun DemoApp(vm: DemoViewModel) {
    MaterialTheme(colorScheme = colors) {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        Scaffold(topBar = { Header(vm) }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                StatusBanner(vm)
                TabRow(selectedTabIndex = tab) {
                    listOf("Door to door", "Drop-off", "Log (${vm.log.size})").forEachIndexed { i, title ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title, maxLines = 1, fontSize = 13.sp) })
                    }
                }
                when (tab) {
                    0 -> CircuitTab(vm)
                    1 -> DropOffTab(vm)
                    else -> LogTab(vm)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Header(vm: DemoViewModel) {
    TopAppBar(
        title = {
            Column {
                Text("RNC waste collection demo", fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1)
                Text(
                    vm.benavVersion?.let { "BeNav $it" } ?: "BeNav not installed",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.8f),
                )
            }
        },
        actions = {
            TextButton(onClick = vm::openBeNav) { Text("BeNav", color = Color.White, maxLines = 1) }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy, titleContentColor = Color.White),
    )
}

@Composable
private fun StatusBanner(vm: DemoViewModel) {
    val error = vm.lastError
    when {
        // Stop stays reachable while starting: BeNav may never confirm the start.
        vm.guidanceRunning || vm.launching -> Row(
            Modifier.fillMaxWidth().background(Color(0xFFE8F5E9)).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${if (vm.guidanceRunning) "Guidance running" else "Starting guidance"} — ${vm.activeScenario?.label}",
                    fontWeight = FontWeight.SemiBold,
                    color = Green,
                )
                vm.remaining?.let { Text(it, fontSize = 13.sp) }
            }
            OutlinedButton(onClick = vm::stopGuidance) { Text("Stop") }
        }
        error != null -> Text(
            error,
            Modifier.fillMaxWidth().background(Color(0xFFFFEBEE)).padding(horizontal = 16.dp, vertical = 8.dp),
            color = Color(0xFFB71C1C),
            fontSize = 13.sp,
        )
    }
}

// ——— Door to door ———

@Composable
private fun CircuitTab(vm: DemoViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importCircuit(uri)
    }
    val o = vm.circuitOptions
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "The waste collection vehicle follows a street-by-street circuit (CircuitNav). If it leaves the circuit, BeNav either routes it back or lets it drive freely.",
            fontSize = 14.sp,
        )
        Section("Circuit") {
            Text(vm.circuitLabel, fontWeight = FontWeight.SemiBold)
            vm.circuit?.let { Text("${it.waypointCount} waypoints · ${it.format.key} format", fontSize = 13.sp, color = Color.Gray) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { picker.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text("Import a JSON") }
                OutlinedButton(onClick = vm::loadDemoCircuit) { Text("Demo circuit") }
            }
        }
        Section("Options") {
            PercentSlider("Circuit start", o.startPercentage) { vm.circuitOptions = o.copy(startPercentage = it.coerceAtMost(o.stopPercentage)) }
            PercentSlider("Circuit end", o.stopPercentage) { vm.circuitOptions = o.copy(stopPercentage = it.coerceAtLeast(o.startPercentage)) }
            SwitchRow(
                "Free drive off the circuit",
                if (o.freeDriveAuto) "No rerouting: the driver finds the way back" else "BeNav reroutes back to the circuit",
                o.freeDriveAuto,
            ) { vm.circuitOptions = o.copy(freeDriveAuto = it) }
            Column {
                Text("Free drive auto-resume: within ${o.freeDriveAutoResumeDistance} m", fontSize = 14.sp)
                Slider(
                    value = o.freeDriveAutoResumeDistance.toFloat(),
                    onValueChange = { vm.circuitOptions = o.copy(freeDriveAutoResumeDistance = (it / 100).roundToInt() * 100) },
                    valueRange = 100f..5000f,
                )
                Text("Guidance resumes by itself if the truck gets back on the circuit no further than this from where it left", fontSize = 12.sp, color = Color.Gray)
            }
            SwitchRow("Hide the part already collected", null, o.hidePassedCircuit) { vm.circuitOptions = o.copy(hidePassedCircuit = it) }
            SwitchRow(
                "Ask the driver when off the circuit",
                if (o.reroutingPopupTimeout > 0) "${o.reroutingPopupTimeout} s popup" else "No popup, default choice",
                o.reroutingPopupTimeout > 0,
            ) { vm.circuitOptions = o.copy(reroutingPopupTimeout = if (it) 20 else 0) }
        }
        Button(onClick = vm::startCircuit, enabled = vm.circuit != null && !vm.launching, modifier = Modifier.fillMaxWidth()) {
            Text(if (vm.launching && vm.activeScenario == Scenario.CIRCUIT) "Sending to BeNav…" else "Start door-to-door collection")
        }
        if (vm.activeScenario == Scenario.CIRCUIT) {
            Section("Circuit progress") {
                val state = vm.circuitState
                val pct = state?.percentage?.let { " (${it.roundToInt()}%)" } ?: ""
                Text(
                    when {
                        state == null -> if (vm.guidanceRunning) "On the circuit" else "Waiting for BeNav…"
                        state.onCircuit -> "On the circuit$pct"
                        else -> "Off the circuit$pct"
                    },
                    fontWeight = FontWeight.SemiBold,
                    color = if (state?.onCircuit == false) Color(0xFFE65100) else Green,
                )
                Button(onClick = vm::joinCircuit, enabled = vm.canJoinCircuit, modifier = Modifier.fillMaxWidth()) {
                    Text("Rejoin the circuit")
                }
                Text(
                    "Enabled when BeNav reports that the truck can rejoin the circuit (free drive).",
                    fontSize = 12.sp,
                    color = Color.Gray,
                )
            }
        }
    }
}

@Composable
private fun PercentSlider(label: String, value: Double, onChange: (Double) -> Unit) {
    Column {
        Text("$label: ${value.roundToInt()}%", fontSize = 14.sp)
        Slider(value = value.toFloat(), onValueChange = { onChange(it.roundToInt().toDouble()) }, valueRange = 0f..100f)
    }
}

// ——— Drop-off points ———

@Composable
private fun DropOffTab(vm: DemoViewModel) {
    val points = DemoData.points
    val done = points.count { vm.pointStatus[it.id] == PointStatus.REACHED || vm.pointStatus[it.id] == PointStatus.SKIPPED }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Round of ${points.size} drop-off points (bring banks), point-to-point guidance.", fontSize = 14.sp)
        }
        item {
            Section("Options") {
                SwitchRow(
                    "Optimize the route",
                    if (vm.optimizeRoute) "BeNav reorders the points for the shortest trip" else "Points visited in list order",
                    vm.optimizeRoute,
                ) { vm.optimizeRoute = it }
                SwitchRow("Show drop-off points on the map", "One icon per waste stream", vm.showDropOffOnMap) { vm.showDropOffOnMap = it }
            }
        }
        item {
            Button(onClick = vm::startDropOff, enabled = !vm.launching, modifier = Modifier.fillMaxWidth()) {
                Text(if (vm.launching && vm.activeScenario == Scenario.DROP_OFF) "Computing the route…" else "Start drop-off collection")
            }
        }
        if (vm.activeScenario == Scenario.DROP_OFF) {
            item { Text("Collected: $done / ${points.size}", fontWeight = FontWeight.SemiBold) }
        }
        items(points, key = { it.id }) { p ->
            val status = vm.pointStatus[p.id]
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).background(Color(p.stream.color), CircleShape), contentAlignment = Alignment.Center) {
                    Text("${p.number}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.label, fontSize = 14.sp)
                    Text(p.stream.label, fontSize = 12.sp, color = Color.Gray)
                }
                when (status) {
                    PointStatus.REACHED -> Icon(Icons.Filled.CheckCircle, "Collected", tint = Green)
                    PointStatus.SKIPPED -> Icon(Icons.Filled.SkipNext, "Skipped", tint = Color(0xFFE65100))
                    PointStatus.PENDING -> Icon(Icons.Filled.RadioButtonUnchecked, "To collect", tint = Color.LightGray)
                    null -> {}
                }
            }
        }
    }
}

// ——— Log ———

@Composable
private fun LogTab(vm: DemoViewModel) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { copy(context, vm.log) }) { Text("Copy") }
            TextButton(onClick = vm::clearLog) { Text("Clear") }
        }
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxSize()) {
            items(vm.log) { entry -> LogRow(entry) }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    var open by remember { mutableStateOf(false) }
    val (arrow, color) = when (entry.direction) {
        Direction.OUT -> "↑" to Navy
        Direction.IN -> "↓" to Green
        Direction.INFO -> "•" to Color.Gray
        Direction.ERROR -> "!" to Color(0xFFB71C1C)
    }
    Column(
        Modifier.fillMaxWidth().clickable(enabled = entry.json.isNotEmpty()) { open = !open }.padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row {
            Text(entry.time, fontSize = 12.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(8.dp))
            Text(arrow, color = color, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(6.dp))
            Text(entry.title, fontSize = 13.sp, color = color)
        }
        if (open) {
            SelectionContainer {
                Text(pretty(entry.json), fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
    HorizontalDivider(color = Color(0xFFEEEEEE))
}

private fun pretty(json: String): String = try {
    org.json.JSONObject(json).toString(2)
} catch (_: Exception) {
    json
}

private fun copy(context: Context, log: List<LogEntry>) {
    val text = log.reversed().joinToString("\n") { "${it.time} ${it.direction} ${it.title}\n${it.json}".trimEnd() }
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("RNC log", text))
}

// ——— Building blocks ———

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F8FA)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = Navy)
            content()
        }
    }
}

@Composable
private fun SwitchRow(label: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    // The whole row toggles, not only the switch.
    Row(
        Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp)
            if (detail != null) Text(detail, fontSize = 12.sp, color = Color.Gray)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
