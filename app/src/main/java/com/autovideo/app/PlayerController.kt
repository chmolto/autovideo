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
 * Dueño del WebView con YouTube. Vive mientras vive el proceso, no la pantalla:
 * se engancha y desengancha de la pantalla del coche (o del móvil) sin parar el vídeo.
 * Todas las llamadas, en el hilo principal.
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

    /** Solo para pruebas (versión debug): el vídeo suena siempre en silencio. */
    @Volatile var testMuted = false
    var playing = false
        private set
    var title: String? = null
        private set
    val hasVideo: Boolean get() = videoId != null

    /** Posición y duración del vídeo en segundos (0 si no se sabe o hay un anuncio). */
    var positionSeconds = 0.0
        private set
    var durationSeconds = 0.0
        private set
    var adShowing = false
        private set

    /** Se llama al cambiar play/pausa, el título o el vídeo. */
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
        // Rechazar cookies no esenciales: evita la pantalla de consentimiento de YouTube.
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
                // Solo se navega dentro de YouTube/Google (consentimiento); el resto se ignora.
                val host = request.url.host ?: return true
                val allowed = ALLOWED_HOST_SUFFIXES.any { host == it || host.endsWith(".$it") }
                if (!allowed) Log.d(TAG, "Navegación ignorada: ${request.url}")
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

    /** Pausa o reanuda según el estado actual (mismo camino que los botones multimedia). */
    fun togglePlay() = if (playing) pause() else play()
    fun play() = js("play()")
    fun pause() = js("pause()")
    fun seekBy(seconds: Int) = js("seek($seconds)")

    /** Salta al segundo [seconds] del vídeo. */
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
                // La duración llega tarde: avisar para que la tarjeta multimedia la muestre.
                if (durationSeconds != oldDuration) onStateChanged?.invoke(playing)
            } catch (e: Exception) {
                Log.w(TAG, "Estado ilegible: $result")
            }
        }
    }

    /** Vuelve a cargar el vídeo actual en el segundo en que iba. */
    fun reload() {
        val id = videoId ?: return
        currentTime { t -> load(id, maxOf(t, 0), autoplay = playing) }
    }

    /** Muestra el reproductor dentro de [container]; [host] es el contexto de esa pantalla. */
    fun attach(host: Context, container: ViewGroup) {
        // Al mover el WebView de pantalla, Chromium pausa el vídeo un instante: si sonaba, reanudarlo.
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
     * Quita el reproductor de la pantalla; el vídeo sigue sonando. El WebView se aparca en
     * una pantalla invisible para que Chromium no lo pause ni frene sus temporizadores.
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
        if (BuildConfig.DEBUG) Log.d(TAG, "orden: $call")
        webView.evaluateJavascript("window.AutoVideo && AutoVideo.$call", null)
    }

    private fun buildScript(): String {
        val css = app.assets.open("inject.css").bufferedReader().use { it.readText() }
        val js = app.assets.open("inject.js").bufferedReader().use { it.readText() }
        return js.replace("__AUTOVIDEO_CSS__", JSONObject.quote(css))
    }

    /** Lo que la página puede decirle a la app. Llega en un hilo de fondo. */
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
