package com.autovideo.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/** Recibe "Compartir → AutoVídeo" (o un enlace de YouTube) y se cierra al momento. */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        val id = text?.let { VideoLink.extractVideoId(it) }
        val message = when {
            id == null -> R.string.share_not_youtube
            Playback.share(this, id) -> R.string.share_playing
            else -> R.string.share_saved
        }
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        finish()
    }
}
