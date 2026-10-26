package com.benomad.rncdemo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service, active for the duration of a guidance. Without it, as soon as
 * BeNav comes to the front, Android freezes the app and delivers the RNC notifications
 * in batches, as rarely as every two minutes on some devices: the displayed tracking
 * (log and circuit %) then moves forward in jerks.
 */
class GuidanceKeepAlive : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "BeNav guidance tracking", NotificationManager.IMPORTANCE_LOW)
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("RNC Demo is following BeNav guidance")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID, notification)
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "guidance"
        private const val ID = 1

        fun start(context: Context) {
            // Refused if the app is not in the foreground (Android 12+): it is started when
            // "Start" is tapped, never from a notification received in the background.
            try {
                ContextCompat.startForegroundService(context, Intent(context, GuidanceKeepAlive::class.java))
            } catch (e: IllegalStateException) {
                Log.w("GuidanceKeepAlive", "Tracking service not started", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GuidanceKeepAlive::class.java))
        }
    }
}
