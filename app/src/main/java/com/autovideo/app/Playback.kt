package com.autovideo.app

import android.content.Context

/** Punto central: un único reproductor por proceso y quién está pendiente de él. */
object Playback {
    private var controller: PlayerController? = null
    private val listeners = mutableSetOf<() -> Unit>()

    /** true mientras la app está abierta en Android Auto. */
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
     * Vídeo compartido desde el móvil. Si el coche está conectado se reproduce ya;
     * si no, se guarda para cuando se abra la app en el coche. Devuelve true si ya suena.
     */
    fun share(ctx: Context, videoId: String): Boolean {
        if (carActive) {
            player(ctx).load(videoId)
            return true
        }
        VideoStore.save(ctx, videoId, 0, autoplay = true)
        return false
    }

    /** Carga el último vídeo guardado (o el compartido pendiente) si no es el que ya está puesto. */
    fun restoreIfEmpty(ctx: Context) {
        val p = player(ctx)
        val saved = VideoStore.load(ctx) ?: return
        if (p.videoId == saved.videoId) return
        p.load(saved.videoId, saved.positionSeconds, saved.autoplay)
    }
}
