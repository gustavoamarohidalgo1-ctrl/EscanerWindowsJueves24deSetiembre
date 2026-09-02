package com.facturastock.app.data.repository

import com.facturastock.app.domain.model.AuditEventType
import com.facturastock.app.domain.repository.AuditPayloadPolicy

/**
 * Escape JSON de las cadenas que el proyecto escribe a mano en payloads de persistencia
 * (auditoría, outbox). Ningún payload lleva serializador: los campos son pocos y cerrados.
 */
internal fun String.jsonEscapedForPayload(): String = buildString(length) {
    this@jsonEscapedForPayload.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) {
                append("\\u%04x".format(character.code))
            } else {
                append(character)
            }
        }
    }
}

/**
 * Objeto JSON determinista para un payload de auditoría clave-valor: claves ordenadas y
 * valores siempre como cadena escapada. Los payloads llevan códigos e identificadores, nunca
 * contenido del comprobante.
 */
internal fun Map<String, String>.toAuditPayloadJson(eventType: AuditEventType): String =
    AuditPayloadPolicy.encode(eventType, this)
