package com.autovideo.app

import android.content.Context

/** Último vídeo y posición, para retomarlo al abrir la app en el coche. */
object VideoStore {
    data class Saved(val videoId: String, val positionSeconds: Int, val autoplay: Boolean)

    private const val PREFS = "video_store"

    fun save(ctx: Context, videoId: String, positionSeconds: Int, autoplay: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("videoId", videoId)
            .putInt("position", positionSeconds)
            .putBoolean("autoplay", autoplay)
            .apply()
    }

    fun load(ctx: Context): Saved? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = p.getString("videoId", null) ?: return null
        return Saved(id, p.getInt("position", 0), p.getBoolean("autoplay", false))
    }
}
