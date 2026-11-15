package com.autovideo.app.car

import android.app.Presentation
import android.graphics.Color
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.util.Log
import android.view.Gravity
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
 */
class SurfaceBridge(private val carContext: CarContext) : SurfaceCallback {

    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var content: FrameLayout? = null
    private var message: TextView? = null
    private var visibleArea: Rect? = null

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
        p.setContentView(root)
        p.show()
        virtualDisplay = vd
        presentation = p
        content = box
        applyVisibleArea()
        refresh()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        Log.d(PlayerController.TAG, "Zona visible $visibleArea")
        this.visibleArea = Rect(visibleArea)
        applyVisibleArea()
    }

    private fun applyVisibleArea() {
        val box = content ?: return
        val area = visibleArea ?: return
        if (area.isEmpty) return
        box.layoutParams = FrameLayout.LayoutParams(area.width(), area.height()).apply {
            leftMargin = area.left
            topMargin = area.top
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
        Playback.currentOrNull?.togglePlay()
    }

    /** Libera la pantalla virtual. El reproductor sigue vivo (audio de fondo). */
    fun release() {
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
}
