package com.facturastock.app.data.spark

import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.AccountException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.firestore.FirebaseFirestoreException
import java.io.IOException

/** Traduce Firestore directo a errores cerrados sin propagar mensajes ni payloads del SDK. */
internal object SparkFirestoreErrorMapper {
    fun fromException(failure: Throwable): AccountError {
        accountExceptionIn(failure)?.let { return it.error }
        return when (failure) {
            is FirebaseFirestoreException -> fromCode(failure.code.name)
            is FirebaseNetworkException, is IOException -> AccountError.NetworkUnavailable
            else -> AccountError.Unexpected
        }
    }

    fun fromCode(code: String): AccountError = when (code) {
        "UNAUTHENTICATED" -> AccountError.SessionExpired
        "PERMISSION_DENIED" -> AccountError.PermissionDenied
        "NOT_FOUND" -> AccountError.NotFound
        "ALREADY_EXISTS", "ABORTED", "FAILED_PRECONDITION" -> AccountError.Conflict
        "UNAVAILABLE", "DEADLINE_EXCEEDED", "CANCELLED", "INTERNAL", "RESOURCE_EXHAUSTED" ->
            AccountError.NetworkUnavailable
        else -> AccountError.Unexpected
    }

    private fun accountExceptionIn(failure: Throwable): AccountException? {
        var current: Throwable? = failure
        repeat(MAX_CAUSE_DEPTH) {
            if (current is AccountException) return current
            val next = current?.cause ?: return null
            if (next === current) return null
            current = next
        }
        return null
    }

    private const val MAX_CAUSE_DEPTH = 8
}
