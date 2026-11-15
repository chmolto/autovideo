package com.autovideo.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** Instrucciones y "Probar aquí": ver el reproductor en el móvil, sin coche. */
class MainActivity : AppCompatActivity() {

    private lateinit var playerBox: FrameLayout
    private var showingPlayer = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        playerBox = FrameLayout(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(TextView(context).apply {
                setText(R.string.instructions)
                textSize = 16f
            })
            addView(Button(context).apply {
                setText(R.string.try_here)
                setOnClickListener { showPlayer() }
            })
            addView(playerBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    private fun showPlayer() {
        Playback.restoreIfEmpty(this)
        val player = Playback.player(this)
        if (!player.hasVideo) {
            Toast.makeText(this, R.string.share_first, Toast.LENGTH_LONG).show()
            return
        }
        player.attach(this, playerBox)
        showingPlayer = true
    }

    override fun onStop() {
        super.onStop()
        if (showingPlayer && Playback.currentOrNull?.webView?.parent === playerBox) {
            Playback.currentOrNull?.detach()
        }
        showingPlayer = false
    }
}
