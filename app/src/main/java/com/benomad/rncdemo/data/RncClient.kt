package com.benomad.rncdemo.data

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.benomad.rncdemo.BuildConfig
import org.json.JSONObject
import java.util.UUID

/**
 * How a command reaches BeNav5.
 *
 * - [ACTIVITY]: `startActivity` on BeNav's `MainActivity`. Works even when
 *   BeNav is stopped, but brings it to the foreground on every send.
 * - [SERVICE]: `sendBroadcast` to BeNav's RNC receiver. BeNav stays in the
 *   background, but must already be running: when stopped, it replies nothing (or
 *   `-7 application.not.started`) and the client falls back to [ACTIVITY] on its own.
 */
enum class Transport { ACTIVITY, SERVICE }

/** What the client sees go by; the ViewModel turns it into the log and the state. */
sealed class RncEvent {
    abstract val json: String

    data class Sent(val id: String, val method: String, val transport: Transport, val attempt: Int, override val json: String) : RncEvent()
    data class Reply(val id: String, val method: String, val message: JSONObject, override val json: String) : RncEvent()
    data class Notification(val method: String, val params: JSONObject, override val json: String) : RncEvent()
    data class GaveUp(val id: String, val method: String, val reason: String, override val json: String = "") : RncEvent()
    /** Received message that matches no known command. */
    data class Other(override val json: String) : RncEvent()
}

/**
 * Sends RNC commands to BeNav5 and receives its replies.
 *
 * Rules learned from field testing, enforced here once and for all:
 * - **only one command in flight**: BeNav answers `-1 busy` to the next one;
 * - `-1 busy` and `-2 application.not.ready`: BeNav did nothing, so resend
 *   **the same id** every 500 ms, at most 6 times (BeNav refuses for ~2.5 s after
 *   startup);
 * - no reply after [replyTimeoutMs]: BeNav swallowed the command
 *   (common at startup), so resend it;
 * - [GAP_MS] between two commands;
 * - `startGuidance` replies **twice** (route, then `result:{}` once guidance
 *   has started): wait for the second one before sending the next command.
 *
 * Ids carry a random prefix drawn at startup ([session]), so that a reply can only be
 * matched to a command of this run. Replies arrive on an exported receiver: any app can
 * send one, and the id is the only thing that ties it to a command.
 *
 * Everything runs on the main thread (Handler): no lock needed.
 */
class RncClient(context: Context, private val onEvent: (RncEvent) -> Unit) {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Pending>()
    private var inFlight: Pending? = null
    private var counter = 0
    private val session = UUID.randomUUID().toString().take(8)

    /** id → method of completed commands, to name a late reply in the log. */
    private val recent = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 50
    }

    private class Pending(
        val id: String,
        val method: String,
        val message: JSONObject,
        var transport: Transport,
        val expectedReplies: Int,
        val replyTimeoutMs: Long,
        val onReply: ((JSONObject) -> Unit)?,
    ) {
        var attempts = 0
        var replies = 0
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            @Suppress("DEPRECATION")
            val raw = intent?.extras?.get("message")?.toString() ?: return
            // Full messages (positions included) only in debug builds.
            if (BuildConfig.DEBUG) Log.d(TAG, "← $raw")
            handler.post { handleMessage(raw) }
        }
    }

    init {
        // RECEIVER_EXPORTED: replies come from another application.
        // Registered on the application context so nothing is lost when BeNav comes to the front.
        ContextCompat.registerReceiver(
            appContext, receiver, IntentFilter(RECEIVE_ACTION), ContextCompat.RECEIVER_EXPORTED,
        )
    }

    fun release() {
        clearQueue()
        appContext.unregisterReceiver(receiver)
    }

    fun nextId(): String = "rncdemo-$session-${++counter}"

    /**
     * Queues a command. [build] receives the assigned id and returns the complete
     * JSON-RPC message (see [RncMessages]).
     */
    fun send(
        transport: Transport,
        expectedReplies: Int = 1,
        replyTimeoutMs: Long = DEFAULT_TIMEOUT_MS,
        onReply: ((JSONObject) -> Unit)? = null,
        build: (id: String) -> JSONObject,
    ) {
        val id = nextId()
        val message = build(id)
        queue.addLast(
            Pending(id, message.optString("method"), message, transport, expectedReplies, replyTimeoutMs, onReply)
        )
        if (inFlight == null) pump(0)
    }

    /**
     * Drops everything pending, including the command in flight (its reply will go to the log).
     * Received messages already posted to the handler are kept.
     */
    fun clearQueue() {
        handler.removeCallbacksAndMessages(PUMP_TOKEN)
        handler.removeCallbacksAndMessages(TIMEOUT_TOKEN)
        inFlight?.let { recent[it.id] = it.method }
        inFlight = null
        queue.clear()
    }

    private fun pump(delayMs: Long) {
        postDelayed(PUMP_TOKEN, delayMs) {
            if (inFlight != null) return@postDelayed
            val next = queue.removeFirstOrNull() ?: return@postDelayed
            inFlight = next
            transmit(next)
        }
    }

    /** Delayed task tagged with [token], so that [clearQueue] removes only the client's own tasks. */
    private fun postDelayed(token: Any, delayMs: Long, block: () -> Unit) {
        handler.postAtTime(block, token, SystemClock.uptimeMillis() + delayMs)
    }

    private fun transmit(p: Pending) {
        p.attempts++
        val json = p.message.toString()
        try {
            val intent = Intent(SEND_ACTION).apply {
                putExtra(EXTRA_NAME, json)
                when (p.transport) {
                    Transport.ACTIVITY -> {
                        // Field-proven: keep the "BND_MESSAGE" data URI as is.
                        data = Uri.parse(EXTRA_NAME)
                        component = ComponentName(BENAV_PACKAGE, BENAV_ACTIVITY)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    Transport.SERVICE -> {
                        component = ComponentName(BENAV_PACKAGE, BENAV_RECEIVER)
                        addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    }
                }
            }
            if (p.transport == Transport.ACTIVITY) appContext.startActivity(intent) else appContext.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Send failed", e)
            finish(p)
            onEvent(RncEvent.GaveUp(p.id, p.method, "BeNav unreachable: ${e.message}", json))
            return
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "→ [${p.transport}] $json")
        onEvent(RncEvent.Sent(p.id, p.method, p.transport, p.attempts, json))
        armTimeout(p, if (p.transport == Transport.SERVICE) SERVICE_TIMEOUT_MS else p.replyTimeoutMs)
    }

    private fun armTimeout(p: Pending, delayMs: Long) {
        handler.removeCallbacksAndMessages(TIMEOUT_TOKEN)
        postDelayed(TIMEOUT_TOKEN, delayMs) { onTimeout(p) }
    }

    private fun onTimeout(p: Pending) {
        if (inFlight !== p) return
        when {
            // A first reply arrived: the command was executed, do not replay it, but say
            // that the next one never came (the caller would otherwise wait forever).
            p.replies > 0 -> {
                finish(p)
                onEvent(RncEvent.GaveUp(p.id, p.method, "Reply ${p.replies + 1} of ${p.expectedReplies} not received"))
            }
            p.attempts < MAX_ATTEMPTS -> {
                // A stopped BeNav does not receive the broadcast and replies nothing (not even -7):
                // only a startActivity starts it.
                if (p.transport == Transport.SERVICE) p.transport = Transport.ACTIVITY
                transmit(p)
            }
            else -> {
                finish(p)
                onEvent(RncEvent.GaveUp(p.id, p.method, "No reply from BeNav after ${p.attempts} attempts"))
            }
        }
    }

    private fun handleMessage(raw: String) {
        val msg = try { JSONObject(raw) } catch (_: Exception) {
            onEvent(RncEvent.Other(raw)); return
        }
        val method = msg.optString("method")
        if (method.isNotEmpty() && !msg.has("result") && !msg.has("error")) {
            onEvent(RncEvent.Notification(method, msg.optJSONObject("params") ?: JSONObject(), raw))
            return
        }
        val id = msg.optString("id")
        val p = inFlight
        if (p == null || p.id != id) {
            val late = recent[id]
            onEvent(if (late != null) RncEvent.Reply(id, late, msg, raw) else RncEvent.Other(raw))
            return
        }
        val code = msg.optJSONObject("error")?.optInt("code")
        when {
            (code == BUSY || code == NOT_READY) && p.replies == 0 -> {
                onEvent(RncEvent.Reply(id, p.method, msg, raw))
                if (p.attempts < MAX_ATTEMPTS) {
                    handler.removeCallbacksAndMessages(TIMEOUT_TOKEN)
                    postDelayed(TIMEOUT_TOKEN, RETRY_DELAY_MS) { if (inFlight === p) transmit(p) }
                } else {
                    finish(p)
                    onEvent(RncEvent.GaveUp(id, p.method, "BeNav still busy after ${p.attempts} attempts"))
                }
            }
            // BeNav stopped: only a startActivity wakes it up.
            code == NOT_STARTED && p.transport == Transport.SERVICE -> {
                onEvent(RncEvent.Reply(id, p.method, msg, raw))
                p.transport = Transport.ACTIVITY
                handler.removeCallbacksAndMessages(TIMEOUT_TOKEN)
                postDelayed(TIMEOUT_TOKEN, RETRY_DELAY_MS) { if (inFlight === p) transmit(p) }
            }
            else -> {
                p.replies++
                onEvent(RncEvent.Reply(id, p.method, msg, raw))
                p.onReply?.invoke(msg)
                if (code != null || p.replies >= p.expectedReplies) finish(p)
                else armTimeout(p, SECOND_REPLY_TIMEOUT_MS)
            }
        }
    }

    private fun finish(p: Pending) {
        if (inFlight !== p) return
        handler.removeCallbacksAndMessages(TIMEOUT_TOKEN)
        recent[p.id] = p.method
        inFlight = null
        pump(GAP_MS)
    }

    companion object {
        private const val TAG = "RncClient"

        const val BENAV_PACKAGE = "com.benomad.benav"
        private const val BENAV_ACTIVITY = "com.benomad.benav.MainActivity"
        private const val BENAV_RECEIVER = "com.benomad.move.rnc_domain.RNCBroadcastReceiver"
        private const val SEND_ACTION = "ACTION_RNC_BROADCAST"
        private const val EXTRA_NAME = "BND_MESSAGE"
        private const val RECEIVE_ACTION = "com.benomad.ACTION_RNC"

        const val BUSY = -1
        const val NOT_READY = -2
        const val NOT_STARTED = -7

        private const val MAX_ATTEMPTS = 6
        private const val RETRY_DELAY_MS = 500L
        private const val GAP_MS = 500L
        const val DEFAULT_TIMEOUT_MS = 4_000L
        /**
         * No reply to the broadcast after this delay: BeNav is most likely stopped.
         * Measured right after a phone reboot: 2.9 s for a BeNav that was running.
         */
        private const val SERVICE_TIMEOUT_MS = 4_000L
        /** Computing a 20-point route or loading a circuit. */
        const val GUIDANCE_TIMEOUT_MS = 8_000L
        private const val SECOND_REPLY_TIMEOUT_MS = 20_000L
        private val TIMEOUT_TOKEN = Any()
        private val PUMP_TOKEN = Any()
    }
}
