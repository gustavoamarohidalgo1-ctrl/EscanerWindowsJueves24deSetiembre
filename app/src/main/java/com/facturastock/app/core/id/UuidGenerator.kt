package com.facturastock.app.core.id

import java.util.UUID

fun interface UuidGenerator {
    fun newUuid(): UUID
}

class RandomUuidGenerator : UuidGenerator {
    override fun newUuid(): UUID = UUID.randomUUID()
}

