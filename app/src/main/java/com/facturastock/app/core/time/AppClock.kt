package com.facturastock.app.core.time

import java.time.Instant

fun interface AppClock {
    fun now(): Instant
}

class SystemAppClock : AppClock {
    override fun now(): Instant = Instant.now()
}

