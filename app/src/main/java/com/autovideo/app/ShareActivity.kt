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
        if (BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_TEST_MUTED, false)) {
            Playback.player(this).testMuted = true
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

    companion object {
        /** Solo en debug: `adb shell am start ... --ez autovideo.test_muted true` para probar sin sonido. */
        const val EXTRA_TEST_MUTED = "autovideo.test_muted"
    }
}
