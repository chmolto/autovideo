package com.autovideo.app.car

import android.content.Intent
import androidx.car.app.AppManager
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.autovideo.app.Playback
import com.autovideo.app.PlaybackService

/** A connection to Android Auto. The video stops on disconnect. */
class AutoVideoSession : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        Playback.carActive = true
        Playback.restoreIfEmpty(carContext)

        val bridge = SurfaceBridge(carContext)
        val screen = PlayerScreen(carContext)
        val listener = {
            bridge.refresh()
            screen.invalidate()
        }
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(bridge)
        Playback.addListener(listener)

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                Playback.removeListener(listener)
                bridge.release()
                Playback.carActive = false
                Playback.currentOrNull?.pause()
                PlaybackService.stop(carContext)
            }
        })
        return screen
    }
}
