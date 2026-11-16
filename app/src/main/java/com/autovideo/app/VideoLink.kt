package com.autovideo.app

import java.net.URI

/** Extracts a YouTube video ID from shared text (which may contain surrounding text). */
object VideoLink {
    private val ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val URL_CANDIDATE = Regex("""(?:https?://)?[A-Za-z0-9.-]+\.[A-Za-z]{2,}(?:/\S*)?""")
    private val YT_HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com")
    private val PATH_PREFIXES = listOf("/shorts/", "/live/", "/embed/")

    fun extractVideoId(text: String): String? =
        URL_CANDIDATE.findAll(text).firstNotNullOfOrNull { idFromUrl(it.value) }

    fun watchUrl(id: String, startSeconds: Int = 0): String {
        val base = "https://m.youtube.com/watch?v=$id"
        return if (startSeconds > 0) "$base&t=${startSeconds}s" else base
    }

    private fun idFromUrl(raw: String): String? {
        val uri = try {
            URI(if (raw.startsWith("http")) raw else "https://$raw")
        } catch (e: Exception) {
            return null
        }
        val host = uri.host?.lowercase() ?: return null
        val path = uri.path ?: ""
        val candidate = when {
            host == "youtu.be" -> path.removePrefix("/").substringBefore('/')
            host in YT_HOSTS && path == "/watch" -> queryParam(uri.rawQuery, "v")
            host in YT_HOSTS -> PATH_PREFIXES.firstOrNull { path.startsWith(it) }
                ?.let { path.removePrefix(it).substringBefore('/') }
            else -> null
        }
        return candidate?.takeIf { ID.matches(it) }
    }

    private fun queryParam(query: String?, name: String): String? =
        query?.split('&')?.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')
}
