package com.hermes.webui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/** Keeps the process alive while a Hermes turn is streaming so leaving the app does not drop SSE. */
class HermesLiveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannel()
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Hermes is working")
            .setContentText("Live chat stays connected in the background")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setColor(0xFFFFD700.toInt())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFY_ID, n)
        }
        return START_STICKY
    }

    private fun ensureChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL, "Live chat", NotificationManager.IMPORTANCE_LOW)
        ch.description = "Keeps a Hermes turn connected when you leave the app"
        ch.setShowBadge(false)
        mgr.createNotificationChannel(ch)
    }

    companion object {
        private const val CHANNEL = "hermes_live"
        private const val NOTIFY_ID = 8787
        const val ACTION_STOP = "com.hermes.webui.STOP_LIVE"

        fun start(ctx: Context) {
            val i = Intent(ctx, HermesLiveService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, HermesLiveService::class.java).setAction(ACTION_STOP))
        }
    }
}
