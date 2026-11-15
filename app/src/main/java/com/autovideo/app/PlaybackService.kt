package com.autovideo.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Servicio en primer plano mientras hay reproducción: mantiene vivo el proceso para que
 * el audio siga aunque la pantalla del coche muestre otra app, y lleva los controles del sistema.
 */
class PlaybackService : Service() {

    private lateinit var media: MediaControls

    override fun onCreate() {
        super.onCreate()
        instance = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        media = MediaControls(this)
        media.update()
        goForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        when (intent?.action) {
            ACTION_TOGGLE -> Playback.currentOrNull?.togglePlay()
            ACTION_STOP -> {
                Playback.currentOrNull?.pause()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun goForeground() {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, media.buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
    }

    private fun refresh() {
        media.update()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, media.buildNotification())
    }

    override fun onDestroy() {
        instance = null
        media.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "playback"
        const val ACTION_TOGGLE = "com.autovideo.app.TOGGLE"
        const val ACTION_STOP = "com.autovideo.app.STOP"
        private const val NOTIFICATION_ID = 1
        private var instance: PlaybackService? = null

        fun start(ctx: Context) {
            if (instance != null) return
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, PlaybackService::class.java))
            } catch (e: Exception) {
                // Android puede negarlo si la app está en segundo plano; se reintenta en el siguiente "play".
                Log.w(PlayerController.TAG, "No se pudo iniciar el servicio en primer plano", e)
            }
        }

        fun update(ctx: Context) {
            instance?.refresh()
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, PlaybackService::class.java))
        }
    }
}
