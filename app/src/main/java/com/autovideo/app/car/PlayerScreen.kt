package com.autovideo.app.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import com.autovideo.app.Playback
import com.autovideo.app.R

/** Pantalla del coche: el vídeo de fondo (superficie) y 4 botones. */
class PlayerScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val player = Playback.player(carContext)
        val strip = ActionStrip.Builder()
            .addAction(
                if (player.playing) action(R.drawable.ic_pause) { player.pause() }
                else action(R.drawable.ic_play) { player.play() },
            )
            .addAction(action(R.drawable.ic_rewind) { player.seekBy(-10) })
            .addAction(action(R.drawable.ic_forward) { player.seekBy(10) })
            .addAction(action(R.drawable.ic_reload) { player.reload() })
            .build()
        return NavigationTemplate.Builder()
            .setActionStrip(strip)
            .build()
    }

    private fun action(icon: Int, onClick: () -> Unit): Action =
        Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, icon)).build())
            .setOnClickListener(onClick)
            .build()
}
