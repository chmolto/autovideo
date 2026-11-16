package com.autovideo.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * Owns the YouTube WebView. It lives as long as the process, not the screen:
 * it attaches to and detaches from the car (or phone) screen without stopping the video.
 * All calls must be made on the main thread.
 */
@SuppressLint("SetJavaScriptEnabled")
class PlayerController(private val app: Context) {

    private val contextWrapper = MutableContextWrapper(app)
    private val offscreen = OffscreenHost(app)
    val webView: WebView = WebView(contextWrapper)
    private val main = Handler(Looper.getMainLooper())
    private val script: String = buildScript()
    private var documentStartScript = false

    @Volatile var videoId: String? = null
        private set
    @Volatile private var autoplay = true

    /** Testing only (debug build): the video always plays muted. */
    @Volatile var testMuted = false
    var playing = false
        private set
    var title: String? = null
        private set
    val hasVideo: Boolean get() = videoId != null

    /** Video position and duration in seconds (0 if unknown or an ad is showing). */
    var positionSeconds = 0.0
        private set
    var durationSeconds = 0.0
        private set
    var adShowing = false
        private set

    /** Called when play/pause, the title or the video changes. */
    var onStateChanged: ((playing: Boolean) -> Unit)? = null

    private val positionSaver = object : Runnable {
        override fun run() {
            savePosition()
            main.postDelayed(this, 5_000)
        }
    }

    private val statusPoller = object : Runnable {
        override fun run() {
            pollStatus()
            main.postDelayed(this, 1_000)
        }
    }

    init {
        // Reject non-essential cookies: avoids YouTube's consent screen.
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setCookie("https://www.youtube.com", "SOCS=CAI; Domain=.youtube.com; Path=/; Secure")
        }
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        webView.setBackgroundColor(Color.BLACK)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }
        webView.addJavascriptInterface(Bridge(), "AutoVideoBridge")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, script, YOUTUBE_ORIGINS)
            documentStartScript = true
        }
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                if (!AdBlockRules.shouldBlock(url)) return null
                Log.d(TAG_ADBLOCK, url)
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Only navigate within YouTube/Google (consent); everything else is ignored.
                val host = request.url.host ?: return true
                val allowed = ALLOWED_HOST_SUFFIXES.any { host == it || host.endsWith(".$it") }
                if (!allowed) Log.d(TAG, "Navigation ignored: ${request.url}")
                return !allowed
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!documentStartScript) view.evaluateJavascript(script, null)
            }
        }
    }

    fun load(videoId: String, startSeconds: Int = 0, autoplay: Boolean = true) {
        this.videoId = videoId
        this.autoplay = autoplay
        title = null
        webView.loadUrl(VideoLink.watchUrl(videoId, startSeconds))
        VideoStore.save(app, videoId, startSeconds, autoplay = false)
        main.removeCallbacks(positionSaver)
        main.postDelayed(positionSaver, 5_000)
        main.removeCallbacks(statusPoller)
        main.post(statusPoller)
        positionSeconds = startSeconds.toDouble()
        durationSeconds = 0.0
        onStateChanged?.invoke(playing)
    }

    /** Pauses or resumes based on the current state (same path as the media buttons). */
    fun togglePlay() = if (playing) pause() else play()
    fun play() = js("play()")
    fun pause() = js("pause()")
    fun seekBy(seconds: Int) = js("seek($seconds)")

    /** Seeks to second [seconds] of the video. */
    fun seekTo(seconds: Double) {
        positionSeconds = seconds
        js("seekTo(${"%.2f".format(java.util.Locale.US, seconds)})")
    }

    private fun pollStatus() {
        webView.evaluateJavascript("window.AutoVideo ? AutoVideo.status() : null") { result ->
            if (result == null || result == "null") return@evaluateJavascript
            try {
                val o = JSONObject(result)
                val oldDuration = durationSeconds
                positionSeconds = o.optDouble("t", 0.0)
                durationSeconds = o.optDouble("d", 0.0)
                adShowing = o.optBoolean("ad", false)
                // The duration arrives late: notify so the media card shows it.
                if (durationSeconds != oldDuration) onStateChanged?.invoke(playing)
            } catch (e: Exception) {
                Log.w(TAG, "Unreadable status: $result")
            }
        }
    }

    /** Reloads the current video at its current position. */
    fun reload() {
        val id = videoId ?: return
        currentTime { t -> load(id, maxOf(t, 0), autoplay = playing) }
    }

    /** Shows the player inside [container]; [host] is that screen's context. */
    fun attach(host: Context, container: ViewGroup) {
        // Moving the WebView to another screen makes Chromium pause the video briefly: resume it if it was playing.
        val wasPlaying = playing
        (webView.parent as? ViewGroup)?.removeView(webView)
        contextWrapper.baseContext = host
        container.addView(
            webView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        if (wasPlaying) {
            for (delay in RESUME_DELAYS_MS) main.postDelayed({ js("resumeAfterMove()") }, delay)
        }
    }

    /**
     * Removes the player from the screen; the video keeps playing. The WebView is parked on
     * an invisible display so Chromium does not pause it or throttle its timers.
     */
    fun detach() {
        savePosition()
        val (ctx, box) = offscreen.container()
        if (webView.parent !== box) attach(ctx, box)
    }

    fun release() {
        main.removeCallbacks(positionSaver)
        main.removeCallbacks(statusPoller)
        (webView.parent as? ViewGroup)?.removeView(webView)
        offscreen.release()
        webView.destroy()
    }

    private fun savePosition() {
        val id = videoId ?: return
        currentTime { t -> if (t >= 0 && id == videoId) VideoStore.save(app, id, t, autoplay = false) }
    }

    private fun currentTime(callback: (Int) -> Unit) {
        webView.evaluateJavascript("window.AutoVideo ? AutoVideo.time() : -1") { result ->
            callback(result?.toDoubleOrNull()?.toInt() ?: -1)
        }
    }

    private fun js(call: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, "command: $call")
        webView.evaluateJavascript("window.AutoVideo && AutoVideo.$call", null)
    }

    private fun buildScript(): String {
        val css = app.assets.open("inject.css").bufferedReader().use { it.readText() }
        val js = app.assets.open("inject.js").bufferedReader().use { it.readText() }
        return js.replace("__AUTOVIDEO_CSS__", JSONObject.quote(css))
    }

    /** What the page can tell the app. Called on a background thread. */
    private inner class Bridge {
        @JavascriptInterface fun videoId(): String = this@PlayerController.videoId ?: ""
        @JavascriptInterface fun autoplay(): Boolean = this@PlayerController.autoplay
        @JavascriptInterface fun testMuted(): Boolean = this@PlayerController.testMuted
        @JavascriptInterface fun isDebug(): Boolean = BuildConfig.DEBUG

        @JavascriptInterface fun onState(isPlaying: Boolean) {
            main.post {
                if (playing != isPlaying) {
                    playing = isPlaying
                    onStateChanged?.invoke(playing)
                }
            }
        }

        @JavascriptInterface fun onTitle(newTitle: String) {
            main.post {
                title = newTitle
                onStateChanged?.invoke(playing)
            }
        }
    }

    companion object {
        const val TAG = "AutoVideo"
        const val TAG_ADBLOCK = "AutoVideo/AdBlock"
        private val YOUTUBE_ORIGINS = setOf("https://m.youtube.com", "https://www.youtube.com", "https://youtube.com")
        private val ALLOWED_HOST_SUFFIXES = listOf("youtube.com", "google.com", "youtu.be")
        private val RESUME_DELAYS_MS = longArrayOf(250, 1000, 2500)
    }
}
