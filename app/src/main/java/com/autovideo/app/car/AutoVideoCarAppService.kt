package com.autovideo.app.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/** Entry point discovered by Android Auto. */
class AutoVideoCarAppService : CarAppService() {
    // Personal, manually installed app: accept any Android Auto host.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = AutoVideoSession()
}
