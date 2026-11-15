package com.autovideo.app

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle

/**
 * Controles de reproducción del sistema (coche y móvil) y notificación.
 *
 * El foco de audio NO se gestiona aquí: lo hace el propio WebView (AudioFocusDelegate de
 * Chromium), que pausa el vídeo si otra app toma el sonido y baja el volumen en indicaciones
 * breves. Pedirlo también desde aquí hacía que nos lo quitáramos a nosotros mismos.
 */
class MediaControls(private val service: Service) {

    private fun player() = Playback.currentOrNull

    private val session = MediaSessionCompat(service, "AutoVideo").apply {
        setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() { player()?.play() }
            override fun onPause() { player()?.pause() }
            override fun onFastForward() { player()?.seekBy(10) }
            override fun onRewind() { player()?.seekBy(-10) }
            override fun onSkipToNext() { player()?.seekBy(10) }
            override fun onSkipToPrevious() { player()?.seekBy(-10) }
            override fun onSeekTo(pos: Long) { player()?.seekTo(pos / 1000.0) }
            override fun onStop() {
                player()?.pause()
                service.stopSelf()
            }
        })
        isActive = true
    }

    fun update() {
        val p = player()
        val playing = p?.playing == true
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_FAST_FORWARD or
                        PlaybackStateCompat.ACTION_REWIND or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_STOP or
                        PlaybackStateCompat.ACTION_SEEK_TO,
                )
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    ((p?.positionSeconds ?: 0.0) * 1000).toLong(),
                    1f,
                )
                .build(),
        )
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, p?.title ?: service.getString(R.string.app_name))
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, service.getString(R.string.app_name))
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, ((p?.durationSeconds ?: 0.0) * 1000).toLong())
                .build(),
        )
    }

    fun buildNotification(): Notification {
        val p = player()
        val playing = p?.playing == true
        val open = PendingIntent.getActivity(
            service, 0, Intent(service, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(service, PlaybackService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_play)
            .setContentTitle(p?.title ?: service.getString(R.string.app_name))
            .setContentText(service.getString(R.string.notification_text))
            .setContentIntent(open)
            .setOngoing(playing)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                service.getString(if (playing) R.string.action_pause else R.string.action_play),
                serviceIntent(PlaybackService.ACTION_TOGGLE, 1),
            )
            .addAction(R.drawable.ic_stop, service.getString(R.string.action_stop), serviceIntent(PlaybackService.ACTION_STOP, 2))
            .setStyle(MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1))
            .build()
    }

    fun release() {
        session.isActive = false
        session.release()
    }

    private fun serviceIntent(action: String, code: Int) = PendingIntent.getService(
        service, code, Intent(service, PlaybackService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
    )
}
