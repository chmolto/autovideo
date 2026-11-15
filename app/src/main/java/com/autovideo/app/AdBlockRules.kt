package com.autovideo.app

import java.net.URI

/**
 * Qué peticiones de red se bloquean. Para añadir reglas, edita estas dos listas.
 */
object AdBlockRules {
    /** Dominios bloqueados por completo (incluye sus subdominios). */
    private val BLOCKED_DOMAINS = listOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "googletagservices.com",
        "imasdk.googleapis.com",
    )

    /** Rutas bloqueadas dentro de los dominios de YouTube. */
    private val BLOCKED_YOUTUBE_PATHS = listOf(
        "/pagead/",
        "/api/stats/ads",
        "/ptracking",
        "/get_midroll_",
        "/pcs/activeview",
    )

    fun shouldBlock(url: String): Boolean {
        val uri = try { URI(url) } catch (e: Exception) { return false }
        val host = uri.host?.lowercase() ?: return false
        if (BLOCKED_DOMAINS.any { host == it || host.endsWith(".$it") }) return true
        val isYoutube = host == "youtube.com" || host.endsWith(".youtube.com")
        val path = uri.rawPath ?: return false
        return isYoutube && BLOCKED_YOUTUBE_PATHS.any { path.startsWith(it) }
    }
}
