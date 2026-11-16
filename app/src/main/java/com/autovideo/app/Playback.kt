package com.autovideo.app

import android.content.Context

/** Central hub: a single player per process and who is listening to it. */
object Playback {
    private var controller: PlayerController? = null
    private val listeners = mutableSetOf<() -> Unit>()

    /** true while the app is open in Android Auto. */
    var carActive = false

    val currentOrNull: PlayerController? get() = controller

    fun player(ctx: Context): PlayerController = controller ?: run {
        val app = ctx.applicationContext
        PlayerController(app).also { c ->
            controller = c
            c.onStateChanged = { playing ->
                if (playing) PlaybackService.start(app)
                PlaybackService.update(app)
                listeners.toList().forEach { it() }
            }
        }
    }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    /**
     * Video shared from the phone. If the car is connected, it plays right away;
     * otherwise it is saved for when the app is opened in the car. Returns true if already playing.
     */
    fun share(ctx: Context, videoId: String): Boolean {
        if (carActive) {
            player(ctx).load(videoId)
            return true
        }
        VideoStore.save(ctx, videoId, 0, autoplay = true)
        return false
    }

    /** Loads the last saved video (or the pending shared one) unless it is already loaded. */
    fun restoreIfEmpty(ctx: Context) {
        val p = player(ctx)
        val saved = VideoStore.load(ctx) ?: return
        if (p.videoId == saved.videoId) return
        p.load(saved.videoId, saved.positionSeconds, saved.autoplay)
    }
}
