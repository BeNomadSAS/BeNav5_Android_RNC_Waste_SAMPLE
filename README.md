# BeNav RNC Waste Collection Demo

Sample Android app that drives **BeNav 5** (`com.benomad.benav`) through **RNC** (Remote
Navigation Control: JSON-RPC 2.0 messages sent with Android intents). It covers two waste
collection use cases in central Nice, France:

- **Door to door**: CircuitNav circuit guidance (`startCircuitGuidance`). The truck follows a
  predefined circuit street by street, and the app tracks its progress.
- **Drop-off points**: point-to-point guidance through 20 bring banks (`startGuidance`), with
  or without stop sequence optimization, with an icon for each waste stream shown on the map
  (`showPoi`).

The **Log** tab shows every JSON message sent and received. Tap an entry to see its full
content.

## Requirements

- An Android device (Android 8.0 or later) with **BeNav 5 installed and activated**.
- To build: Android Studio Narwhal 3 Feature Drop (2025.1.3) or later (Android Gradle Plugin
  8.13), or JDK 17+ and the Android SDK (API 34) for the command line.

Tested with BeNav 2.3.0.1 on Android 16: both guidance types (drop-off with and without
optimization), drop-off icons on the map, stop, free drive with automatic resume, and
resubscription after a BeNav restart.

## Build and install

With Android Studio, open the project folder and run the `app` configuration.

From the command line, with the device connected over USB (USB debugging enabled):

```
./gradlew installDebug
```

To build without installing: `./gradlew assembleDebug`, which produces
`app/build/outputs/apk/debug/app-debug.apk`.

Unit tests (JVM only, no device needed): `./gradlew testDebugUnitTest`.

## Trying CircuitNav

1. Open **Door to door**. The demo circuit is already loaded: 2.3 km and 43 waypoints in the
   Musiciens district of Nice.
2. Optionally set the options, which map one-to-one to the `startCircuitGuidance` parameters
   (see the table below).
3. Tap **Start door-to-door collection**. BeNav comes to the foreground and starts guiding.
4. Back in the app, **Circuit progress** shows the progress (%), and whether the truck is on
   or off the circuit. In free drive, **Rejoin the circuit** sends `joinCircuit` once BeNav
   reports that a rejoin is possible.

To test without driving, use the route simulation of BeNav if your build provides it, and
start it from the circuit start.

### Using your own circuit

Tap **Import a JSON** and pick a file stored on the device, for example one copied with
`adb push my_circuit.json /sdcard/Download/`. The app recognizes:

| File content | Sent as `requestFormat` |
|---|---|
| A full `startCircuitGuidance` command (`params.requestFormat` + data) | as declared |
| A BeMap traceroute response (`routingRoutes[0].waypoints`, `coordinate.lon/lat`) | `traceRoute` |
| A Fleex payload (`routeData.waypoints`, `longitude/latitude`) | `fleex` |
| A BeNav 4 `startCircuitNav` command or waypoints with `mPos` | `benav4` |
| A bare `waypoints` array | detected from the waypoint fields |

The parser is in `app/src/main/java/com/benomad/rncdemo/data/CircuitParser.kt`.

**How to build a good circuit with BeMap.** Call
`POST /bgis/service/routing/1.0/traceroute` with the options `WAYPOINTS` and
`NO_MINIMAL_WAYPOINTS`, and feed it a trace with **about one point every 50 m**. BeNav
computes the path between two consecutive waypoints itself (Emergency mode, Fastest
criterion). With sparse waypoints, it may take a different street from the one you planned.
`app/src/main/assets/circuit_nice_musiciens.json` is an example of such a response.

## RNC in a nutshell

| | |
|---|---|
| Command intent | action `ACTION_RNC_BROADCAST`, JSON in the string extra `BND_MESSAGE` |
| To BeNav's activity | component `com.benomad.benav/.MainActivity`, `startActivity` (brings BeNav to the foreground) |
| To BeNav's receiver | component `com.benomad.benav/com.benomad.move.rnc_domain.RNCBroadcastReceiver`, `sendBroadcast` (BeNav stays in the background) |
| Replies and notifications | broadcast `com.benomad.ACTION_RNC`, JSON in the extra `message` |

The transport code is in `data/RncClient.kt`, and the JSON messages are built in
`data/RncMessages.kt` (plain Kotlin, unit tested).

### `startCircuitGuidance` parameters used

| Parameter | Meaning | App default |
|---|---|---|
| `requestFormat` + `<format>.waypoints` | the circuit | `traceRoute` |
| `startPercentage` / `stopPercentage` | part of the circuit to drive | 0 / 100 |
| `freeDriveAuto` | off the circuit: `false` reroutes back to the circuit, `true` switches to free drive | `false` |
| `freeDriveAutoResumeDistance` | in free drive, guidance resumes automatically if the truck gets back on the circuit within this distance (m) | 3000 |
| `reroutingPopupTimeout` | seconds the driver has to choose between rerouting and free drive; 0 = no popup | 20 |
| `hidePassedCircuit` | hide the part already driven | `true` |
| `blockStartPercentage` | blocked section before `startPercentage`; 100 = disabled | 100 |
| `startUTurnThreshold` | taken from the traceroute response when present | 3000 |

### CircuitNav notifications

These are always on: do **not** list them in `setupNotifications`, or the whole command is
rejected.

- `onCircuitLeft` / `onCircuitJoined`: `{percentage, lat, lon}`.
- `onCanJoinCircuit`: `{percentage}`, repeated every second in free drive. `joinCircuit` is
  only accepted after this notification.

BeNav reports the circuit percentage only when the truck leaves and rejoins the circuit. While
the truck is on the circuit, the app derives the percentage from `distanceToDest`
(`onNavigationProgressChanged`) and the circuit length (`routingRoutes[0].length`). See
`CircuitPayload.percentageAt`.

### Field notes

- Send **one command at a time**, and wait for its reply before sending the next one. On
  `-1` (busy) or `-2` (not ready), send the **same id** again after 500 ms.
- A **stopped BeNav does not answer a broadcast at all**. Without a reply within about 4 s,
  the app resends the command through the activity, which starts BeNav.
- Navigation notifications are off by default, and the subscription is lost when BeNav
  restarts. Send `setupNotifications` before each guidance, and again on
  `onAppStatusChanged` / `startupComplete`. The minimum `minPeriod` of
  `onNavigationProgressChanged` is 30 s.
- `startGuidance` replies twice (route computed, then guidance started).
  `startCircuitGuidance` replies once.
- `showPoi` is all-or-nothing: one unreadable icon and nothing is shown. Icons can be
  `https://` or `content://` URIs granted to `com.benomad.benav`, never `file://`
  (see `data/PoiIcons.kt`).
- While BeNav is in the foreground, Android may freeze the app and deliver notifications in
  batches. The app runs a small foreground service during guidance (`GuidanceKeepAlive.kt`)
  to receive them in real time.
- `onAppStatusChanged` is on by default, without any `setupNotifications`: that is what lets
  the app subscribe again when BeNav restarts.
- A guidance command can take several seconds to answer while BeNav computes the route: the
  app waits 8 s (instead of 4 s) before resending it.
- `onCanJoinCircuit` arrives every second in free drive: the app uses it but leaves it out of
  the log.
- When importing a full `startCircuitGuidance` command, the app keeps the waypoints and
  `startUTurnThreshold`; the other options come from the screen.

## Security notes

- Replies come back as a broadcast that any app can send: the app's receiver is exported.
  The app only accepts replies whose id matches the command in flight, and ids carry a random
  per-session prefix, so another app cannot easily forge a reply. Notifications (no id) are
  trusted as-is: do not drive critical actions from them without your own checks.
- Full JSON messages are written to logcat in debug builds only.
- The release build is unsigned: add your own `signingConfig` to distribute it.

## Project layout

```
app/src/main/java/com/benomad/rncdemo/
  data/RncMessages.kt     JSON-RPC commands (plain Kotlin, unit tested)
  data/CircuitParser.kt   circuit file recognition (unit tested)
  data/RncClient.kt       send queue, retries, replies
  data/PoiIcons.kt        drop-off point icons for showPoi
  data/DemoData.kt        the 20 drop-off points and the demo circuit name
  DemoViewModel.kt        both scenarios, displayed state, log
  GuidanceKeepAlive.kt    foreground service during guidance
  ui/DemoApp.kt           Jetpack Compose UI (Door to door, Drop-off, Log)
app/src/main/assets/      demo circuit and icons
```

## License

Copyright (c) 2026 BeNomad. This sample code may only be used to integrate with the BeNav
navigation application: see [LICENSE](LICENSE). BeNav 5 is licensed separately.
