package com.autovideo.app

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout

/**
 * Pantalla virtual invisible donde "aparcar" el WebView cuando no se ve en ningún sitio.
 * Así Chromium lo considera visible: no pausa el vídeo ni frena los temporizadores
 * (de los que depende saltar anuncios) mientras el coche muestra otra app.
 */
class OffscreenHost(private val app: Context) {

    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var root: FrameLayout? = null

    /** Contexto y contenedor donde enganchar el WebView. Se crea la primera vez. */
    fun container(): Pair<Context, FrameLayout> {
        root?.let { return presentation!!.context to it }
        val main = Handler(Looper.getMainLooper())
        val r = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 2).apply {
            // Descartar los fotogramas: nadie los mira, pero hay que consumirlos.
            setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, main)
        }
        val vd = app.getSystemService(DisplayManager::class.java).createVirtualDisplay(
            "AutoVideoOffscreen", WIDTH, HEIGHT, DPI, r.surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
        )
        val p = Presentation(app, vd.display)
        val box = FrameLayout(p.context).apply { setBackgroundColor(Color.BLACK) }
        p.setContentView(box)
        p.show()
        reader = r
        display = vd
        presentation = p
        root = box
        return p.context to box
    }

    fun release() {
        presentation?.dismiss()
        display?.release()
        reader?.close()
        presentation = null
        display = null
        reader = null
        root = null
    }

    private companion object {
        const val WIDTH = 640
        const val HEIGHT = 360
        const val DPI = 160
    }
}
