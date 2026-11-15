package com.autovideo.app.car

import android.app.Presentation
import android.graphics.Color
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.hardware.display.VirtualDisplay
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import com.autovideo.app.Playback
import com.autovideo.app.PlayerController
import com.autovideo.app.R

/**
 * Convierte la superficie que da Android Auto en una "pantalla" Android normal
 * (VirtualDisplay + Presentation) donde se muestra el WebView del reproductor.
 * El vídeo se ajusta a la zona visible (p. ej. el hueco izquierdo en la vista dividida).
 *
 * Toques: con la barra de progreso oculta, un toque solo la muestra. Con la barra visible,
 * tocar la franja de la barra salta a ese momento y tocar el vídeo pausa/reanuda.
 */
class SurfaceBridge(private val carContext: CarContext) : SurfaceCallback {

    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var content: FrameLayout? = null
    private var message: TextView? = null
    private var overlay: ProgressOverlay? = null
    private var visibleArea: Rect? = null
    private val main = Handler(Looper.getMainLooper())
    private var hideAt = 0L

    /** Refresca la barra mientras se ve y la oculta pasado un rato (no si está en pausa). */
    private val overlayTicker = object : Runnable {
        override fun run() {
            val bar = overlay ?: return
            val p = Playback.currentOrNull
            if (p != null) bar.update(p.positionSeconds, p.durationSeconds, p.adShowing)
            val paused = p != null && p.hasVideo && !p.playing
            if (!paused && System.currentTimeMillis() > hideAt) {
                bar.visibility = View.GONE
                return
            }
            main.postDelayed(this, 250)
        }
    }

    private val player: PlayerController get() = Playback.player(carContext)

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        val surface = surfaceContainer.surface ?: return
        release()
        Log.d(PlayerController.TAG, "Superficie ${surfaceContainer.width}x${surfaceContainer.height} @${surfaceContainer.dpi}dpi")
        val dm = carContext.getSystemService(DisplayManager::class.java)
        val vd = dm.createVirtualDisplay(
            "AutoVideo",
            surfaceContainer.width,
            surfaceContainer.height,
            surfaceContainer.dpi,
            surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
        )
        val p = Presentation(carContext, vd.display)
        val root = FrameLayout(p.context).apply { setBackgroundColor(Color.BLACK) }
        val box = FrameLayout(p.context)
        root.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val bar = ProgressOverlay(p.context).apply { visibility = View.GONE }
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        p.setContentView(root)
        p.show()
        virtualDisplay = vd
        presentation = p
        content = box
        overlay = bar
        applyVisibleArea()
        refresh()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        Log.d(PlayerController.TAG, "Zona visible $visibleArea")
        this.visibleArea = Rect(visibleArea)
        applyVisibleArea()
    }

    private fun applyVisibleArea() {
        val area = visibleArea ?: return
        if (area.isEmpty) return
        for (v in listOfNotNull(content, overlay)) {
            v.layoutParams = FrameLayout.LayoutParams(area.width(), area.height()).apply {
                leftMargin = area.left
                topMargin = area.top
            }
        }
    }

    private fun showOverlay() {
        val bar = overlay ?: return
        if (!player.hasVideo) return
        hideAt = System.currentTimeMillis() + OVERLAY_MS
        if (bar.visibility != View.VISIBLE) {
            bar.visibility = View.VISIBLE
            main.removeCallbacks(overlayTicker)
            main.post(overlayTicker)
        }
    }

    /** Muestra el vídeo si hay uno, o el aviso de "comparte un vídeo". */
    fun refresh() {
        val box = content ?: return
        val p = presentation ?: return
        if (player.hasVideo) {
            message?.let { box.removeView(it) }
            message = null
            if (player.webView.parent !== box) player.attach(p.context, box)
            if (!player.playing) showOverlay() // en pausa, la barra se queda a la vista
        } else if (message == null) {
            message = TextView(p.context).apply {
                setText(R.string.car_empty)
                setTextColor(Color.WHITE)
                textSize = 28f
                gravity = Gravity.CENTER
            }
            box.addView(message, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) = release()

    override fun onClick(x: Float, y: Float) {
        val p = Playback.currentOrNull ?: return
        val bar = overlay
        if (bar == null || bar.visibility != View.VISIBLE) {
            showOverlay() // primer toque: solo enseñar la barra
            return
        }
        val area = visibleArea
        val fraction = bar.fractionAt(x - (area?.left ?: 0), y - (area?.top ?: 0))
        if (fraction != null && p.durationSeconds > 0) {
            p.seekTo(fraction * p.durationSeconds)
        } else {
            p.togglePlay()
        }
        showOverlay()
    }

    /** Libera la pantalla virtual. El reproductor sigue vivo (audio de fondo). */
    fun release() {
        main.removeCallbacks(overlayTicker)
        overlay = null
        val box = content
        val current = Playback.currentOrNull
        if (box != null && current != null && current.webView.parent === box) current.detach()
        presentation?.dismiss()
        virtualDisplay?.release()
        presentation = null
        virtualDisplay = null
        content = null
        message = null
    }

    private companion object {
        const val OVERLAY_MS = 5_000L
    }
}
