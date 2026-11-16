package com.autovideo.app

import java.net.URI

/**
 * Which network requests are blocked. To add rules, edit these two lists.
 */
object AdBlockRules {
    /** Fully blocked domains (including their subdomains). */
    private val BLOCKED_DOMAINS = listOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "googletagservices.com",
        "imasdk.googleapis.com",
    )

    /** Blocked paths within YouTube domains. */
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
