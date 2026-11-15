package com.autovideo.app.car

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View

/**
 * Barra de progreso dibujada sobre el vídeo en la pantalla del coche:
 * franja oscura abajo con la línea de progreso, un punto y "12:34 / 45:00".
 * Tocar sobre la franja salta a ese momento (lo decide [SurfaceBridge] con [fractionAt]).
 */
class ProgressOverlay(context: Context) : View(context) {

    private var position = 0.0
    private var duration = 0.0
    private var ad = false

    private val density = resources.displayMetrics.density
    private val bandHeight = 72 * density
    private val sideMargin = 28 * density
    private val trackY get() = height - 24 * density

    private val shade = Paint()
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 255, 255, 255); strokeWidth = 5 * density; strokeCap = Paint.Cap.ROUND
    }
    private val done = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0xFF, 0x30, 0x30); strokeWidth = 5 * density; strokeCap = Paint.Cap.ROUND
    }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xFF, 0x30, 0x30) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 18 * density; typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(3 * density, 0f, 0f, Color.BLACK)
    }

    fun update(positionSeconds: Double, durationSeconds: Double, adShowing: Boolean) {
        position = positionSeconds
        duration = durationSeconds
        ad = adShowing
        invalidate()
    }

    /**
     * Si (x, y) cae sobre la franja de la barra, la fracción del vídeo (0..1) en esa x;
     * si no, null. Coordenadas relativas a esta vista.
     */
    fun fractionAt(x: Float, y: Float): Float? {
        if (visibility != VISIBLE || duration <= 0 || y < height - bandHeight) return null
        val w = width - 2 * sideMargin
        if (w <= 0) return null
        return ((x - sideMargin) / w).coerceIn(0f, 1f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        shade.shader = LinearGradient(
            0f, h - bandHeight * 1.4f, 0f, h.toFloat(),
            Color.TRANSPARENT, Color.argb(200, 0, 0, 0), Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        canvas.drawRect(0f, h - bandHeight * 1.4f, width.toFloat(), h, shade)
        val label = when {
            ad -> "Anuncio…"
            duration > 0 -> "${format(position)} / ${format(duration)}"
            else -> format(position)
        }
        canvas.drawText(label, sideMargin, trackY - 16 * density, text)
        val left = sideMargin
        val right = width - sideMargin
        canvas.drawLine(left, trackY, right, trackY, track)
        if (duration > 0 && !ad) {
            val x = left + (right - left) * (position / duration).toFloat().coerceIn(0f, 1f)
            canvas.drawLine(left, trackY, x, trackY, done)
            canvas.drawCircle(x, trackY, 9 * density, knob)
        }
    }

    private fun format(seconds: Double): String {
        val s = seconds.toLong().coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }
}
