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
 * Foreground service while playing: keeps the process alive so the audio continues
 * even when the car screen shows another app, and hosts the system controls.
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
                // Android may refuse this if the app is in the background; it is retried on the next "play".
                Log.w(PlayerController.TAG, "Could not start the foreground service", e)
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
