package com.autovideo.app.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/** Punto de entrada que descubre Android Auto. */
class AutoVideoCarAppService : CarAppService() {
    // App personal instalada a mano: se acepta cualquier host de Android Auto.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = AutoVideoSession()
}
